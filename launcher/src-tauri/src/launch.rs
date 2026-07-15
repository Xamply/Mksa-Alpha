//! Pipeline de lanzamiento. Implementa la secuencia obligatoria de "Jugar"
//! del protocolo IPC §1.5: instancia única, launching.lock para la ventana
//! ciega, spawn con el agente inyectado.

use crate::instances::{self, Instance};
use crate::{ipc, jvm, meta};
use serde::Serialize;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Runtime};

#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Serialize)]
pub struct LaunchResult {
    pub already_running: bool,
    pub pid: u32,
}

/// Juegos lanzados por ESTE proceso del launcher. El token vive solo aquí
/// (contrato: nunca toca el disco); un launcher reiniciado puede detectar el
/// juego vivo y prohibir el doble lanzamiento, pero no re-autenticar el IPC.
/// Resolver ese traspaso queda anotado como pregunta abierta del protocolo.
pub struct RunningGame {
    pub pid: u32,
    pub token: String,
}

pub type Games = Arc<Mutex<HashMap<String, RunningGame>>>;

pub fn emit_game<R: Runtime>(
    app: &AppHandle<R>,
    instance: &str,
    kind: &str,
    extra: &[(&str, String)],
) {
    let mut payload = serde_json::json!({ "instance": instance, "kind": kind });
    for (k, v) in extra {
        payload[k] = serde_json::Value::String(v.clone());
    }
    let _ = app.emit("mksa://game", payload);
}

pub fn launch<R: Runtime>(
    app: AppHandle<R>,
    games: Games,
    instance_path: String,
) -> Result<LaunchResult, String> {
    let inst = instances::read_instance(Path::new(&instance_path))
        .ok_or("la carpeta ya no parece una instancia")?;
    let mksa_dir = instances::mksa_dir(&instance_path);
    fs::create_dir_all(&mksa_dir).map_err(|e| e.to_string())?;

    // §1.5 paso 1-3: ¿ya está corriendo?
    if let Some(pid) = live_game_pid(&mksa_dir) {
        return Ok(LaunchResult {
            already_running: true,
            pid,
        });
    }

    // §1.5 paso 4: ventana ciega.
    check_and_write_launch_lock(&mksa_dir)?;
    let result = launch_inner(&app, &games, &inst, &mksa_dir);
    if result.is_err() {
        let _ = fs::remove_file(mksa_dir.join("launching.lock"));
    }
    result
}

fn launch_inner<R: Runtime>(
    app: &AppHandle<R>,
    games: &Games,
    inst: &Instance,
    mksa_dir: &Path,
) -> Result<LaunchResult, String> {
    let instance_path = inst.path.clone();
    let progress_counter = std::sync::atomic::AtomicU32::new(0);
    let resolved = meta::resolve(inst, &|msg| {
        // Los ~130 artefactos pasan en milisegundos cuando están en disco;
        // solo informamos uno de cada 25 para no inundar la UI.
        let n = progress_counter.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        if n % 25 == 0 {
            emit_game(
                app,
                &instance_path,
                "spawning",
                &[("detail", msg.to_string())],
            );
        }
    })?;

    emit_game(
        app,
        &instance_path,
        "spawning",
        &[("detail", "resolviendo JVM".into())],
    );
    let java = jvm::resolve(inst.java_path.as_deref(), &resolved.java_majors, &|msg| {
        emit_game(
            app,
            &instance_path,
            "spawning",
            &[("detail", msg.to_string())],
        );
    })?;

    let agent_jar = find_agent_jar()?;
    let token = random_token();

    let sep = if cfg!(windows) { ";" } else { ":" };
    let classpath = resolved
        .classpath
        .iter()
        .map(|p| p.to_string_lossy().to_string())
        .collect::<Vec<_>>()
        .join(sep);

    let player = std::env::var("USERNAME").unwrap_or_else(|_| "Player".into());
    let game_args = build_game_args(&resolved, &inst.minecraft_dir, &player);

    let mut cmd = Command::new(&java.launch_exe);
    cmd.arg(format!("-Xms{}m", inst.min_mem_mb))
        .arg(format!("-Xmx{}m", inst.max_mem_mb));
    // Fase 9: JBR21 es la JVM dedicada de MKSA (jvm::resolve() la fuerza para
    // todo lanzamiento real que requiera Java 21). La flag es inerte hasta
    // que algo llame redefineClasses con cambio de forma -- se agrega siempre
    // que la JVM resuelta sea realmente JBR (nunca por heurística de
    // versión: `is_jbr` solo es true cuando vino de `jvm::ensure_jbr()`, así
    // que una JVM estándar jamás recibe una flag que no reconoce).
    if java.is_jbr {
        cmd.arg("-XX:+AllowEnhancedClassRedefinition");
    }
    // Fase 8: flags JVM extra inyectados solo por el harness de verificación.
    // La ruta de producción no define esta variable.
    if let Ok(extra) = std::env::var("MKSA_EXTRA_JVM_ARGS") {
        for arg in extra.split_whitespace() {
            cmd.arg(arg);
        }
    }
    cmd.arg(format!("-javaagent:{}", agent_jar.to_string_lossy()))
        .arg(format!("-Dmksa.token={token}"))
        .arg(format!("-Dmksa.dir={}", mksa_dir.to_string_lossy()))
        .arg("-Dminecraft.launcher.brand=MKSA");
    if let Some(fw) = &resolved.forgewrapper {
        cmd.arg(format!(
            "-Dforgewrapper.librariesDir={}",
            fw.libraries_dir.to_string_lossy()
        ))
        .arg(format!(
            "-Dforgewrapper.installer={}",
            fw.installer.to_string_lossy()
        ));
        if let Some(client) = &resolved.client_jar {
            cmd.arg(format!(
                "-Dforgewrapper.minecraft={}",
                client.to_string_lossy()
            ));
        }
    }
    cmd.arg("-cp")
        .arg(&classpath)
        .arg(&resolved.main_class)
        .args(&game_args)
        .current_dir(&inst.minecraft_dir);
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);

    emit_game(
        app,
        &instance_path,
        "spawning",
        &[(
            "detail",
            format!(
                "lanzando Minecraft {} (Java {})",
                resolved.mc_version, java.major
            ),
        )],
    );
    let child = cmd
        .spawn()
        .map_err(|e| format!("no se pudo lanzar la JVM: {e}"))?;
    let pid = child.id();

    games.lock().unwrap().insert(
        instance_path.clone(),
        RunningGame {
            pid,
            token: token.clone(),
        },
    );

    // Supervisor: espera agent.json, conecta IPC, reenvía eventos a la UI.
    let app2 = app.clone();
    let games2 = games.clone();
    let mksa2 = mksa_dir.to_path_buf();
    std::thread::spawn(move || {
        ipc::supervise(app2, games2, instance_path, mksa2, token, child);
    });

    Ok(LaunchResult {
        already_running: false,
        pid,
    })
}

/// Fase 3, corte 1 — relaunch con un mod desactivado.
///
/// Demuestra la transición controlada entre dos configuraciones de mods sobre el
/// MISMO mundo, vía dos JVMs. Es SECUENCIAL por el `session.lock` de Minecraft
/// (un solo escritor del mundo): cierra el juego vivo con gracia —guarda y suelta
/// el mundo— ANTES de mover los jars y lanzar el proceso nuevo. El solape (B
/// arranca tras la pantalla de recarga de A) y el traspaso de estado del jugador
/// son cortes posteriores. T0 es el primer corte correcto: solo-datos, sin bloques
/// en el mundo, así que desactivarlo no necesita barrido ni migración por época.
///
/// `disable_jars` son rutas absolutas de jar (de `state.mods[].files`) a excluir
/// de `mods/`. Se mueven a `<instancia>/mksa/disabled/` (reversible con
/// `restore_mods`). Devuelve el `LaunchResult` del proceso nuevo.
pub fn relaunch_disable<R: Runtime>(
    app: AppHandle<R>,
    games: Games,
    instance_path: String,
    disable_jars: Vec<String>,
) -> Result<LaunchResult, String> {
    let inst = instances::read_instance(Path::new(&instance_path))
        .ok_or("la carpeta ya no parece una instancia")?;
    let mksa_dir = instances::mksa_dir(&instance_path);
    fs::create_dir_all(&mksa_dir).map_err(|e| e.to_string())?;

    // 1. Cerrar el juego vivo: guarda el mundo y libera el session.lock. Sin esto,
    //    el proceso nuevo no podría abrir el mundo (un solo escritor en SP).
    if let Some(pid) = live_game_pid(&mksa_dir) {
        emit_game(
            &app,
            &instance_path,
            "relaunch",
            &[("detail", "cerrando el juego actual".into())],
        );
        close_game_gracefully(pid);
    }
    // Restos del proceso viejo: el shutdown hook del agente borra agent.json, pero
    // si no alcanzó lo limpiamos para que el lanzamiento nuevo no se confunda.
    let _ = fs::remove_file(mksa_dir.join("agent.json"));
    let _ = fs::remove_file(mksa_dir.join("launching.lock"));
    games.lock().unwrap().remove(&instance_path);

    // 2. Excluir los jars del modset nuevo (reversible).
    let moved = stage_out_mods(&mksa_dir, &disable_jars)?;
    emit_game(
        &app,
        &instance_path,
        "relaunch",
        &[("detail", format!("{} mod(s) desactivado(s)", moved.len()))],
    );

    // 3. Lanzar el proceso nuevo sobre el MISMO mundo, saltando el guard de
    //    instancia única (§1.5): un relaunch es la única excepción legítima a
    //    "no abrir dos veces" — el proceso viejo ya murió en el paso 1.
    launch_inner(&app, &games, &inst, &mksa_dir)
}

/// Mueve los jar indicados de `mods/` a `<instancia>/mksa/disabled/`. Reversible
/// con `restore_mods`. Devuelve (origen, destino) por cada jar movido. Un jar que
/// no exista en disco (mod anidado, ruta dentro de un zip-FS) se omite. Error si
/// no se movió ninguno — el relaunch no tendría efecto observable.
pub fn stage_out_mods(mksa_dir: &Path, jars: &[String]) -> Result<Vec<(PathBuf, PathBuf)>, String> {
    let disabled = mksa_dir.join("disabled");
    fs::create_dir_all(&disabled).map_err(|e| e.to_string())?;
    let mut moved = Vec::new();
    for j in jars {
        let src = PathBuf::from(j);
        if !src.is_file() {
            continue; // anidado o ya ausente: nada que mover
        }
        let name = src
            .file_name()
            .ok_or_else(|| format!("jar sin nombre: {}", src.display()))?;
        let dst = disabled.join(name);
        fs::rename(&src, &dst)
            .map_err(|e| format!("no se pudo desactivar {}: {e}", src.display()))?;
        moved.push((src, dst));
    }
    if moved.is_empty() {
        return Err("ningún jar movible (¿mod anidado o ruta inexistente?)".into());
    }
    Ok(moved)
}

/// Devuelve a `mods/` los jar movidos por `stage_out_mods` (reactivación / limpieza).
pub fn restore_mods(moved: &[(PathBuf, PathBuf)]) -> Result<(), String> {
    for (orig, staged) in moved {
        if staged.is_file() {
            fs::rename(staged, orig)
                .map_err(|e| format!("no se pudo restaurar {}: {e}", orig.display()))?;
        }
    }
    Ok(())
}

/// Cierra el juego con GRACIA: WM_CLOSE (taskkill SIN /F) dispara el guardado-y-
/// salida normal de Minecraft y libera el lock del mundo limpiamente. Matar en
/// seco a media escritura de un `.mca` corrompe chunks (la saga de P3). Espera a
/// que el proceso muera; si no cierra en 60 s (diálogo, cuelgue), escala a /F como
/// red de seguridad. Lógica de producción del relaunch — no solo del harness.
#[cfg(windows)]
pub fn close_game_gracefully(pid: u32) {
    let mut cmd = Command::new("taskkill");
    cmd.args(["/PID", &pid.to_string(), "/T"]);
    cmd.creation_flags(CREATE_NO_WINDOW);
    let _ = cmd.output();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(60);
    while std::time::Instant::now() < deadline {
        if !pid_alive(pid) {
            return;
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
    let mut force = Command::new("taskkill");
    force.args(["/PID", &pid.to_string(), "/F", "/T"]);
    force.creation_flags(CREATE_NO_WINDOW);
    let _ = force.output();
}

#[cfg(not(windows))]
pub fn close_game_gracefully(pid: u32) {
    let _ = Command::new("kill")
        .args(["-TERM", &pid.to_string()])
        .status();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(60);
    while std::time::Instant::now() < deadline {
        if !pid_alive(pid) {
            return;
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
    let _ = Command::new("kill")
        .args(["-KILL", &pid.to_string()])
        .status();
}

/// §1.5: la verdad de "¿está corriendo?" es pid vivo + puerto que responde,
/// nunca la mera existencia del archivo.
fn live_game_pid(mksa_dir: &Path) -> Option<u32> {
    let contact = mksa_dir.join("agent.json");
    let text = fs::read_to_string(&contact).ok()?;
    let v: serde_json::Value = serde_json::from_str(&text).ok()?;
    let pid = v["pid"].as_u64()? as u32;
    let port = v["port"].as_u64()? as u16;
    if pid_alive(pid) && port_listening(port) {
        return Some(pid);
    }
    // Huérfano: un crash no debe bloquear el siguiente lanzamiento.
    let _ = fs::remove_file(&contact);
    None
}

fn check_and_write_launch_lock(mksa_dir: &Path) -> Result<(), String> {
    let lock = mksa_dir.join("launching.lock");
    if let Ok(text) = fs::read_to_string(&lock) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
            let lock_pid = v["pid"].as_u64().unwrap_or(0) as u32;
            let ts = v["ts"].as_u64().unwrap_or(0);
            let age_ms = now_ms().saturating_sub(ts);
            if pid_alive(lock_pid) && age_ms < 60_000 {
                return Err("Esta instancia ya se está lanzando.".into());
            }
        }
        let _ = fs::remove_file(&lock);
    }
    let content = serde_json::json!({ "pid": std::process::id(), "ts": now_ms() });
    fs::write(&lock, content.to_string()).map_err(|e| e.to_string())
}

fn build_game_args(r: &meta::ResolvedLaunch, minecraft_dir: &str, player: &str) -> Vec<String> {
    let uuid = offline_uuid(player);
    let subst = |k: &str| -> String {
        match k {
            "auth_player_name" => player.into(),
            "version_name" => r.mc_version.clone(),
            "game_directory" => minecraft_dir.into(),
            "assets_root" => r.assets_dir.to_string_lossy().into(),
            "assets_index_name" => r.asset_index_id.clone(),
            "auth_uuid" => uuid.clone(),
            "auth_access_token" => "0".into(),
            "user_type" => "legacy".into(),
            "user_properties" => "{}".into(),
            "version_type" => "MKSA".into(),
            _ => String::new(),
        }
    };
    let mut args: Vec<String> = r
        .mc_args_template
        .split_whitespace()
        .map(|tok| {
            if let Some(k) = tok.strip_prefix("${").and_then(|t| t.strip_suffix('}')) {
                subst(k)
            } else {
                tok.to_string()
            }
        })
        .collect();
    // Gancho de verificación: entrar directo a un mundo singleplayer. `--quickPlaySingleplayer`
    // es vanilla de Mojang (QuickPlay, 1.20+) y Fabric/NeoForge lo pasan al juego. Solo lo usa
    // el harness del ledger (vía MKSA_QUICKPLAY_WORLD); la ruta de producción no se ve afectada.
    if let Ok(world) = std::env::var("MKSA_QUICKPLAY_WORLD") {
        if !world.is_empty() {
            args.push("--quickPlaySingleplayer".into());
            args.push(world);
        }
    }
    args
}

/// UUID offline estable derivado del nombre (el cliente no lo valida).
fn offline_uuid(player: &str) -> String {
    let mut h = sha1_smol::Sha1::new();
    h.update(format!("OfflinePlayer:{player}").as_bytes());
    let d = h.digest().bytes();
    let mut b = [0u8; 16];
    b.copy_from_slice(&d[..16]);
    b[6] = (b[6] & 0x0f) | 0x30; // versión 3
    b[8] = (b[8] & 0x3f) | 0x80; // variante RFC 4122
    let hex: String = b.iter().map(|x| format!("{x:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

/// El jar del agente viaja con el launcher. Orden: variable de entorno (dev),
/// junto al ejecutable (release), o subiendo desde el ejecutable hasta
/// encontrar agent/dist (árbol de desarrollo).
fn find_agent_jar() -> Result<PathBuf, String> {
    if let Ok(p) = std::env::var("MKSA_AGENT_JAR") {
        let p = PathBuf::from(p);
        if p.is_file() {
            return Ok(p);
        }
    }
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let beside = exe.with_file_name("fable-agent.jar");
    if beside.is_file() {
        return Ok(beside);
    }
    let mut dir = exe.parent();
    while let Some(d) = dir {
        let cand = d.join("agent").join("dist").join("fable-agent.jar");
        if cand.is_file() {
            return Ok(cand);
        }
        dir = d.parent();
    }
    Err(
        "No se encontró fable-agent.jar (compila agent/ con build.sh o define MKSA_AGENT_JAR)."
            .into(),
    )
}

fn random_token() -> String {
    use rand::RngCore;
    let mut b = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut b);
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub fn pid_alive(pid: u32) -> bool {
    if pid == 0 {
        return false;
    }
    #[cfg(windows)]
    {
        let mut cmd = Command::new("tasklist");
        cmd.args(["/FI", &format!("PID eq {pid}"), "/NH", "/FO", "CSV"]);
        cmd.creation_flags(CREATE_NO_WINDOW);
        match cmd.output() {
            Ok(out) => String::from_utf8_lossy(&out.stdout).contains(&format!("\"{pid}\"")),
            Err(_) => false,
        }
    }
    #[cfg(not(windows))]
    {
        std::path::Path::new(&format!("/proc/{pid}")).exists()
            || Command::new("kill")
                .args(["-0", &pid.to_string()])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
    }
}

fn port_listening(port: u16) -> bool {
    std::net::TcpStream::connect_timeout(
        &std::net::SocketAddr::from(([127, 0, 0, 1], port)),
        std::time::Duration::from_millis(800),
    )
    .is_ok()
}
