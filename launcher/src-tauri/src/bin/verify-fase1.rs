//! Harness de verificación de la Fase 1 contra la instancia real de pruebas.
//!
//! Es un bin (no un test) a propósito: tauri-build solo embebe el manifest de
//! Windows (common-controls v6) en los binarios, y sin él el exe ni carga
//! (STATUS_ENTRYPOINT_NOT_FOUND en comctl32).
//!
//!   cargo run --bin verify-fase1            → scanner + classpath + JVM, sin lanzar
//!   cargo run --bin verify-fase1 -- --launch → lanza Minecraft real, verifica §1.5
//!                                              (contacto, handshake, instancia única)
//!                                              y mata el proceso al terminar.
//!   cargo run --bin verify-fase1 -- --jbr    → fuerza la descarga silenciosa de JBR
//!   cargo run --bin verify-fase1 -- --fase2  → como --launch, y además verifica el
//!                                              subconjunto F2: boot.stage hasta
//!                                              game-ready, state.*, wcg.definitions
//!   cargo run --bin verify-fase1 -- --fase2-neo → lo mismo contra la instancia
//!                                              NeoForge real (ForgeWrapper, mojmap)
//!   cargo run --bin verify-fase1 -- --fase2-ledger → de-risk del ledger (inc.5 Paso 1):
//!                                              entra a "mksa-test" con --quickPlaySingleplayer,
//!                                              espera el autosave, confirma mksa:prov en el
//!                                              .mca y el roundtrip (readWithProv>0 al releer)
//!   cargo run --bin verify-fase1 -- --fase2-ledger2 → ledger inc.5 Paso 2: época real
//!                                              (state.epoch) + agregado de presencia
//!                                              (wcg.counts) escaneando palettes
//!   cargo run --bin verify-fase1 -- --fase2-ledger3 → ledger inc.5 Paso 3: ledger en
//!                                              vivo (de-risk). Hook de LevelChunk.
//!                                              setBlockState dispara (setBlocks>0 con
//!                                              las mutaciones vanilla del tick) y el
//!                                              índice de bloques modded se construye
//!                                              (moddedBlocks>0), sin crash
//!   cargo run --bin verify-fase1 -- --fase3-relaunch → Fase 3 corte 1: transición
//!                                              controlada entre dos modsets sobre el
//!                                              mismo mundo, vía dos JVMs. Lanza A,
//!                                              elige un mod T0 hoja, cierra A con
//!                                              gracia, lo excluye de mods/, lanza B
//!                                              sobre el mismo mundo y confirma que B
//!                                              no lo ve, la época cambió, el mundo
//!                                              cargó intacto y no hay corrupción
//!   cargo run --bin verify-fase1 -- --fase3-relaunch2 → Fase 3 corte 2: víctima CON
//!                                              huella real en el mundo. El usuario
//!                                              coloca bloques de mcwstairs en A; tras
//!                                              el relaunch sin el mod, observa per-chunk
//!                                              (sello de época × presencia del ns) si
//!                                              B conservó (A), descartó (A') o no
//!                                              reserializó (inconcluso) la huella.
//!   cargo run --bin verify-fase1 -- --inproc1 → In-process corte 1 (§3.5): el agente
//!                                              retiene un handle vivo a ServerLevel y
//!                                              marshaliza al hilo del servidor para
//!                                              mutar el mundo EN CALIENTE (setBlock
//!                                              reversible sobre el spawn), sin reiniciar.
//!   cargo run --bin verify-fase1 -- --tier1-1 → Tier 1 corte 1 (in-process): el agente
//!                                              localiza vía el ledger los chunks vivos
//!                                              con presencia de mcwstairs, captura su
//!                                              footprint al MKSAR1 (simétrico al corte 3
//!                                              del relaunch) y lo reemplaza por aire en
//!                                              vivo. Estrena el ledger como mecanismo
//!                                              in-process; el agregado wcg.counts queda
//!                                              obsoleto post-sweep (aggregateStale:true).
//!   cargo run --bin verify-fase1 -- --tier1-2 → Tier 1 corte 2 (in-process): paga la
//!                                              deuda técnica del corte 1. Sustituye
//!                                              el binomio dummies+saveAllChunks por
//!                                              SerializableChunkData.copyOf(level,
//!                                              chunk).write() sobre los chunks
//!                                              cargados del view distance (captura
//!                                              quirúrgica zero-IO). Pieza 2: byChunk
//!                                              incremental desde onSetBlock con el
//!                                              prevState (hook movido al ARETURN);
//!                                              wcg.counts queda honesto en vivo y
//!                                              aggregateStale pasa a false.
//!   cargo run --bin verify-fase1 -- --tier1-3 → Tier 1 corte 3 (in-process): filtro
//!                                              de registro vivo. Inyecta al ENTRY de
//!                                              LevelChunk.setBlockState una rama de
//!                                              veto: si shouldVeto(newState)==true,
//!                                              devuelve null (señal canónica de MC
//!                                              "no hubo cambio") — caller no envía
//!                                              packet, item no se consume. Verifica
//!                                              que con veto armado el usuario no
//!                                              puede colocar, y que tras desarmar
//!                                              vuelve a colocar normalmente (idempotente).
//!   cargo run --bin verify-fase1 -- --tier1-4 → Tier 1 corte 4 (in-process): reload
//!                                              dirigido de datapacks. Sin tocar mods/,
//!                                              arma el filtro y dispara
//!                                              MinecraftServer.reloadResources(packs)
//!                                              programáticamente; los hooks de
//!                                              RecipeManager.prepare y
//!                                              TagManagerLoader.build descartan in-place
//!                                              recetas/tags del ns víctima ANTES de la
//!                                              fase apply. Verifica: recipesFiltered>0
//!                                              (mcwstairs añade recetas de stairs),
//!                                              recipe/tagHookCalls>0 (los hooks corrieron),
//!                                              ningún crash, instancia restaurada al cierre.
//!   cargo run --bin verify-fase1 -- --tier1-5 → Tier 1 corte 5 (in-process): envoltorio
//!                                              transaccional. Una sola llamada
//!                                              tx.tier1Disable{ns,restorePath} orquesta
//!                                              vetoArm + snapshot + sweep + dpReload y
//!                                              devuelve un TxReceipt explícito (txId,
//!                                              status, steps con veredicto y métricas).
//!                                              Verifica receipt completo, tx.history, y
//!                                              que tx.tier1Enable {ns} desarma el veto y
//!                                              repuebla recetas (status OK, steps
//!                                              ["vetoDisarm","dpReload"]). NO restituye
//!                                              bloques al mundo vivo — ese es corte 5b.

use mksa_launcher_lib::{instances, jvm, launch, meta};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::Path;

const ROOT: &str = "E:/Users/Aly/Desktop/MKSA";
const INSTANCIA: &str = "E:/Users/Aly/Desktop/MKSA/Instancia 3";
const INSTANCIA_NEO: &str = "C:/Users/Aly/AppData/Roaming/PrismLauncher/instances/1.21.5";

/// Expectativas de la verificación F2, por instancia.
struct F2Expect {
    loader: &'static str,
    mc: &'static str,
    names: &'static str,
    /// namespace de un mod real con definiciones en los registros
    mod_ns: &'static str,
    min_mods: usize,
}

const FABRIC_EXPECT: F2Expect = F2Expect {
    loader: "fabric",
    mc: "1.21.11",
    names: "intermediary",
    mod_ns: "waystones",
    min_mods: 16,
};

const NEO_EXPECT: F2Expect = F2Expect {
    loader: "neoforge",
    mc: "1.21.5",
    names: "mojmap",
    mod_ns: "neoforge",
    min_mods: 17, // 16 mods + minecraft + neoforge
};

fn main() {
    let do_launch = std::env::args().any(|a| a == "--launch");
    let do_jbr = std::env::args().any(|a| a == "--jbr");
    let do_fase2 = std::env::args().any(|a| a == "--fase2");
    let do_neo = std::env::args().any(|a| a == "--fase2-neo");
    let do_ledger = std::env::args().any(|a| a == "--fase2-ledger");
    let do_ledger2 = std::env::args().any(|a| a == "--fase2-ledger2");
    let do_ledger3 = std::env::args().any(|a| a == "--fase2-ledger3");
    let do_fase3 = std::env::args().any(|a| a == "--fase3-relaunch");
    let do_fase3b = std::env::args().any(|a| a == "--fase3-relaunch2");
    let do_fase3_inv = std::env::args().any(|a| a == "--fase3-inv");
    let do_fase3_sweep = std::env::args().any(|a| a == "--fase3-sweep");
    let do_fase3_restore = std::env::args().any(|a| a == "--fase3-restore");
    let do_fase3_restore_items = std::env::args().any(|a| a == "--fase3-restore-items");
    let do_fase3_restore_probe = std::env::args().any(|a| a == "--fase3-restore-probe");
    let do_inproc1 = std::env::args().any(|a| a == "--inproc1");
    let do_tier1_1 = std::env::args().any(|a| a == "--tier1-1");
    let do_tier1_2 = std::env::args().any(|a| a == "--tier1-2");
    let do_tier1_3 = std::env::args().any(|a| a == "--tier1-3");
    let do_tier1_4 = std::env::args().any(|a| a == "--tier1-4");
    let do_tier1_5 = std::env::args().any(|a| a == "--tier1-5");
    let do_probe_recipes = std::env::args().any(|a| a == "--probe-recipes");
    let do_probe_tags = std::env::args().any(|a| a == "--probe-tags");
    let do_probe_loot = std::env::args().any(|a| a == "--probe-loot");
    let do_wcg_refs = std::env::args().any(|a| a == "--wcg-refs");
    if do_neo {
        resolve_check_neo();
        launch_check(INSTANCIA_NEO, true, &NEO_EXPECT);
        println!("\nVERIFICACIÓN OK");
        return;
    }
    resolve_check();
    if do_jbr {
        jbr_check();
    }
    if do_probe_loot {
        probe_loot_check(INSTANCIA);
    } else if do_probe_tags {
        probe_tags_check(INSTANCIA);
    } else if do_wcg_refs {
        wcg_refs_check(INSTANCIA);
    } else if do_probe_recipes {
        probe_recipes_check(INSTANCIA);
    } else if do_tier1_5 {
        tier1_5_check(INSTANCIA);
    } else if do_tier1_4 {
        tier1_4_check(INSTANCIA);
    } else if do_tier1_3 {
        tier1_3_check(INSTANCIA);
    } else if do_tier1_2 {
        tier1_2_check(INSTANCIA);
    } else if do_tier1_1 {
        tier1_1_check(INSTANCIA);
    } else if do_inproc1 {
        inproc1_check(INSTANCIA);
    } else if do_fase3_restore_probe {
        fase3_restore_probe(INSTANCIA);
    } else if do_fase3_restore_items {
        fase3_restore_items_check(INSTANCIA);
    } else if do_fase3_restore {
        fase3_restore_check(INSTANCIA);
    } else if do_fase3_sweep {
        fase3_sweep_check(INSTANCIA);
    } else if do_fase3_inv {
        fase3_inv_check(INSTANCIA);
    } else if do_fase3b {
        fase3_relaunch2_check(INSTANCIA);
    } else if do_fase3 {
        fase3_relaunch_check(INSTANCIA);
    } else if do_ledger3 {
        ledger3_check(INSTANCIA);
    } else if do_ledger2 {
        ledger2_check(INSTANCIA);
    } else if do_ledger {
        ledger_check(INSTANCIA);
    } else if do_launch || do_fase2 {
        launch_check(INSTANCIA, do_fase2, &FABRIC_EXPECT);
    }
    println!("\nVERIFICACIÓN OK");
}

/// Fuerza la descarga/instalación de JBR aunque haya JVMs del sistema
/// (el fallback no salta solo en esta máquina porque ya existe Java 21).
fn jbr_check() {
    println!("[jbr] descarga silenciosa de JBR");
    let java = jvm::ensure_jbr(&|msg| println!("       {msg}")).expect("ensure_jbr");
    check(java.launch_exe.is_file(), &format!("ejecutable en {}", java.launch_exe.display()));
    check(java.major == 21, "JBR es Java 21");
}

fn check(cond: bool, what: &str) {
    if cond {
        println!("  ✔ {what}");
    } else {
        println!("  ✘ {what}");
        std::process::exit(1);
    }
}

fn resolve_check() {
    println!("[1/2] resolución (sin lanzar)");
    let list = instances::scan(ROOT).expect("scan");
    check(
        list.iter().any(|i| i.path.replace('\\', "/") == INSTANCIA),
        "Instancia 3 detectada por el scanner",
    );

    let inst = instances::read_instance(Path::new(INSTANCIA)).expect("instancia");
    check(inst.mc_version.as_deref() == Some("1.21.11"), "versión MC 1.21.11");
    check(inst.loader_name.as_deref() == Some("Fabric"), "loader Fabric");
    check(inst.mod_count > 0, &format!("{} mods detectados", inst.mod_count));

    let resolved = meta::resolve(&inst, &|_| {}).expect("resolución de classpath");
    check(
        resolved.main_class == "net.fabricmc.loader.impl.launch.knot.KnotClient",
        "mainClass es KnotClient (el loader manda)",
    );
    check(
        resolved.classpath.iter().all(|p| p.is_file()),
        &format!("los {} jars del classpath existen en disco", resolved.classpath.len()),
    );
    let cp = resolved
        .classpath
        .iter()
        .map(|p| p.to_string_lossy().to_string())
        .collect::<Vec<_>>()
        .join(";");
    check(cp.contains("fabric-loader-0.18.4"), "fabric-loader en el classpath");
    check(cp.contains("minecraft-1.21.11-client"), "client jar en el classpath");
    check(cp.contains("intermediary-1.21.11"), "intermediary en el classpath");

    let java = jvm::resolve(inst.java_path.as_deref(), &resolved.java_majors, &|msg| {
        println!("       {msg}");
    })
    .expect("JVM");
    check(
        resolved.java_majors.contains(&java.major),
        &format!("JVM compatible: java {} en {}", java.major, java.launch_exe.display()),
    );
}

/// Resolución de la instancia NeoForge real: ForgeWrapper como mainClass,
/// instalador y client jar identificados, args del loader presentes.
fn resolve_check_neo() {
    println!("[1/2] resolución NeoForge (sin lanzar)");
    let inst = instances::read_instance(Path::new(INSTANCIA_NEO)).expect("instancia");
    check(inst.loader_name.as_deref() == Some("NeoForge"), "loader NeoForge");
    check(inst.mc_version.as_deref() == Some("1.21.5"), "versión MC 1.21.5");
    check(inst.mod_count > 0, &format!("{} mods detectados", inst.mod_count));

    let resolved = meta::resolve(&inst, &|_| {}).expect("resolución de classpath");
    check(
        resolved.main_class == "io.github.zekerzhayard.forgewrapper.installer.Main",
        "mainClass es ForgeWrapper (el loader manda)",
    );
    check(
        resolved.classpath.iter().all(|p| p.is_file()),
        &format!("los {} jars del classpath existen en disco", resolved.classpath.len()),
    );
    let fw = resolved.forgewrapper.as_ref();
    check(
        fw.is_some_and(|f| f.installer.is_file()),
        "instalador de NeoForge en disco",
    );
    check(
        fw.is_some_and(|f| f.libraries_dir.is_dir()),
        "librariesDir único para ForgeWrapper",
    );
    check(
        resolved.client_jar.as_ref().is_some_and(|p| p.is_file()),
        "client jar vanilla identificado",
    );
    check(
        resolved.mc_args_template.contains("--launchTarget"),
        "minecraftArguments del loader (--launchTarget)",
    );

    let java = jvm::resolve(inst.java_path.as_deref(), &resolved.java_majors, &|msg| {
        println!("       {msg}");
    })
    .expect("JVM");
    check(
        resolved.java_majors.contains(&java.major),
        &format!("JVM compatible: java {} en {}", java.major, java.launch_exe.display()),
    );
}

fn launch_check(instancia: &str, fase2: bool, exp: &F2Expect) {
    println!("[2/2] lanzamiento real");
    let app = tauri::test::mock_app();
    let games = launch::Games::default();

    let result = launch::launch(app.handle().clone(), games.clone(), instancia.into())
        .expect("lanzamiento");
    check(!result.already_running, &format!("juego lanzado, pid {}", result.pid));

    // Contacto del agente (premain).
    let contact = Path::new(instancia).join("mksa").join("agent.json");
    let mut waited = 0u32;
    while !contact.is_file() {
        if waited >= 120_000 {
            check(false, "agent.json apareció antes de 120s");
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
        waited += 500;
    }
    let v: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&contact).unwrap()).unwrap();
    let port = v["port"].as_u64().unwrap() as u16;
    check(true, &format!("contacto publicado tras ~{waited}ms (pid={} port={port})", v["pid"]));

    // Instancia única (§1.5).
    let second = launch::launch(app.handle().clone(), games.clone(), instancia.into())
        .expect("segundo launch");
    check(second.already_running, "segundo Jugar devolvió already_running (instancia única)");

    // Handshake con el token real (vive solo en memoria del launcher).
    let token = games.lock().unwrap().get(instancia).map(|g| g.token.clone());
    if let Some(token) = token {
        let mut s = std::net::TcpStream::connect(("127.0.0.1", port)).expect("connect");
        let hello = serde_json::json!({
            "t":"req","id":1,"m":"hello",
            "p":{"proto":1,"side":"launcher","version":"verify","token":token}
        });
        s.write_all(format!("{hello}\n").as_bytes()).unwrap();
        let mut reader = BufReader::new(s.try_clone().unwrap());
        let mut line = String::new();
        reader.read_line(&mut line).unwrap();
        let res: serde_json::Value = serde_json::from_str(&line).unwrap();
        check(
            res["ok"].is_object(),
            &format!("handshake aceptado (sesión {})", res["ok"]["session"]),
        );
        if fase2 {
            fase2_check(&mut s, &mut reader, res["ok"]["boot"].as_str().unwrap_or("premain"), exp);
        }
    } else {
        check(false, "token del juego presente en el mapa");
    }

    let pid = result.pid;
    if !fase2 {
        // Modo manual (--launch solo): el harness no mata el juego — espera a que
        // tu lo cierres. Asi tienes el plazo que necesites para abrir el mundo y
        // probar el panel del mod thin. Override por MKSA_LAUNCH_WAIT_SECS para
        // forzar un timeout fijo (compat con uso viejo).
        if let Ok(secs) = std::env::var("MKSA_LAUNCH_WAIT_SECS").map(|s| s.parse::<u64>()) {
            let secs = secs.unwrap_or(45);
            println!("  … MKSA_LAUNCH_WAIT_SECS={secs}: esperando {secs}s y matando");
            std::thread::sleep(std::time::Duration::from_secs(secs));
            check(launch::pid_alive(pid), "el juego sigue vivo");
            println!("  … cerrando el juego (fin de la verificación)");
            kill_pid(pid);
        } else {
            println!("  … juego lanzado (pid {pid}). Pruebalo, cierra Minecraft cuando termines.");
            println!("     (Ctrl+C aqui aborta el harness; MC sigue vivo si lo haces)");
            loop {
                std::thread::sleep(std::time::Duration::from_secs(2));
                if !launch::pid_alive(pid) {
                    println!("  ✔ el juego cerro limpiamente (pid {pid})");
                    break;
                }
            }
        }
    } else {
        check(launch::pid_alive(pid), "el juego sigue vivo");
        println!("  … cerrando el juego (fin de la verificación)");
        kill_pid(pid);
    }
}

/// Subconjunto F2 del protocolo: boot.stage hasta game-ready, state.*, NOT_READY en wcg.*.
fn fase2_check(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    boot_at_hello: &str,
    exp: &F2Expect,
) {
    println!("[fase2] etapas de arranque y state.*");
    s.set_read_timeout(Some(std::time::Duration::from_secs(240))).unwrap();

    let mut stage = boot_at_hello.to_string();
    let mut stages_seen = vec![stage.clone()];
    while stage != "game-ready" {
        let v = read_msg(reader);
        if v["t"] == "evt" && v["m"] == "boot.stage" {
            stage = v["p"]["stage"].as_str().unwrap_or("?").to_string();
            stages_seen.push(stage.clone());
            println!("       boot.stage {stage} {}", v["p"]["detail"]);
        } else if v["t"] == "evt" && v["m"] == "boot.failed" {
            check(false, &format!("boot.failed: {}", v["p"]));
        }
    }
    let expected = ["loader-detected", "mappings-resolved", "registries-frozen", "wcg-ready"];
    check(
        expected.iter().all(|e| stages_seen.contains(&e.to_string())),
        &format!("etapas en orden: {}", stages_seen.join(" → ")),
    );

    let snap = request(s, reader, 2, "state.snapshot");
    check(
        snap["ok"]["loader"]["name"] == exp.loader,
        &format!("snapshot: loader {}", exp.loader),
    );
    check(snap["ok"]["mc"] == exp.mc, &format!("snapshot: MC {}", exp.mc));
    check(
        snap["ok"]["names"] == exp.names,
        &format!("snapshot: nombres {}", exp.names),
    );
    let n_mods = snap["ok"]["mods"].as_array().map(|a| a.len()).unwrap_or(0);
    check(
        n_mods >= exp.min_mods,
        &format!("snapshot: {n_mods} contenedores de mods (≥{})", exp.min_mods),
    );

    let mods = request(s, reader, 3, "state.mods");
    let sodium = mods["ok"]["mods"]
        .as_array()
        .and_then(|a| a.iter().find(|m| m["id"] == "fabric-api" || m["id"] == "sodium"));
    check(
        sodium.is_some_and(|m| m["version"].is_string() && m["enabled"] == true),
        "state.mods: mods reales con id/version/enabled",
    );

    // Tier v0 (§3.4): clasificación estructural. Todo mod queda clasificado
    // (0–3, ya no null), y un set real tiene al menos un mod con código (T2/T3).
    let all_mods = mods["ok"]["mods"].as_array().cloned().unwrap_or_default();
    let tiers: Vec<i64> = all_mods.iter().filter_map(|m| m["tier"].as_i64()).collect();
    check(
        tiers.len() == all_mods.len() && tiers.iter().all(|t| (0..=3).contains(t)),
        &format!(
            "state.mods: {} mods clasificados en tier 0–3 (ninguno null)",
            tiers.len()
        ),
    );
    check(
        tiers.iter().any(|&t| t >= 2),
        "state.mods: al menos un mod con código (T2/T3)",
    );
    let dist = |t: i64| tiers.iter().filter(|&&x| x == t).count();
    println!(
        "       distribución de tiers: T0={} T1={} T2={} T3={}",
        dist(0), dist(1), dist(2), dist(3)
    );
    // inc.4: el refinamiento T1↔T2 por capacidad estática debe separar al menos
    // un mod de solo-registro. En Fabric el set tiene mods de solo-contenido
    // (mcw-*, connectedglass…) que no se suscriben a buses → T1≥1. En NeoForge
    // T1 puede ser 0 legítimamente (set perf/cliente casi todo T3, y
    // DeferredRegister arrastra IEventBus hasta en mods de solo-registro).
    if exp.loader == "fabric" {
        check(
            dist(1) >= 1,
            &format!("state.mods: refinamiento T1↔T2 disparó (Fabric T1={} ≥ 1)", dist(1)),
        );
    }

    let page = request_p(s, reader, 4, "wcg.definitions",
        serde_json::json!({ "ns": "minecraft", "page": { "limit": 10 } }));
    let n = page["ok"]["items"].as_array().map(|a| a.len()).unwrap_or(0);
    check(
        n == 10 && page["ok"]["next"].is_string(),
        &format!("wcg.definitions paginado: {n} items, next={}", page["ok"]["next"]),
    );

    let prefix = format!("{}:", exp.mod_ns);
    let ws = request_p(s, reader, 5, "wcg.definitions", serde_json::json!({ "ns": exp.mod_ns }));
    let ws_items = ws["ok"]["items"].as_array().cloned().unwrap_or_default();
    check(
        !ws_items.is_empty()
            && ws_items.iter().all(|i| i["id"].as_str().is_some_and(|s| s.starts_with(&prefix))),
        &format!(
            "wcg.definitions ns={}: {} definiciones de un mod real",
            exp.mod_ns,
            ws_items.len()
        ),
    );

    let ent = request_p(s, reader, 6, "wcg.definitions",
        serde_json::json!({ "ns": "minecraft", "reg": "minecraft:entity_type" }));
    let ent_items = ent["ok"]["items"].as_array().cloned().unwrap_or_default();
    check(
        !ent_items.is_empty() && ent_items.iter().all(|i| i["reg"] == "minecraft:entity_type"),
        &format!("wcg.definitions reg=entity_type: {} entidades vanilla", ent_items.len()),
    );

    let bad = request_p(s, reader, 7, "wcg.definitions", serde_json::json!({}));
    check(bad["err"]["code"] == "BAD_PARAMS", "wcg.definitions sin ns: BAD_PARAMS");
}

// ─────────────────────────── ledger (inc.5 Paso 1) ───────────────────────────

/// Nombre de la sección de procedencia, tal cual la escribe el Bridge.
const PROV_KEY: &[u8] = b"mksa:prov";

/// De-risk del ledger: ¿corre el bytecode inyectado y persiste `mksa:prov` en el
/// .mca? Dos lanzamientos contra "mksa-test" (que ya existe, escrito antes del
/// ledger → sin sección):
///   A. WRITE — entra al mundo, espera el autosave (write()→Bridge bootstrap),
///      confirma `written>0`, mata el juego y busca `mksa:prov` en region/*.mca.
///   B. READ-back — recarga el mundo ya estampado y confirma `readWithProv>0`:
///      parse() detecta la sección que escribió A. Cierra el roundtrip.
fn ledger_check(instancia: &str) {
    println!("[ledger] de-risk inc.5 Paso 1: roundtrip mksa:prov");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    let region_dir = Path::new(&inst.minecraft_dir)
        .join("saves")
        .join("mksa-test")
        .join("region");
    check(
        region_dir.is_dir(),
        &format!("mundo 'mksa-test' presente ({})", region_dir.display()),
    );
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();

    // ── Lanzamiento A: escritura + persistencia ──
    println!("[ledger] lanzamiento A (escritura; los write() ocurren en el churn de carga de chunks)");
    let (pid_a, mut s, mut reader) = open_session(&handle, &games, instancia);

    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(480);
    let mut id = 2u64;
    let (written, read_total) = loop {
        let (w, rw, rt) = ledger_stats(&mut s, &mut reader, id);
        id += 1;
        println!("       ledger.stats written={w} readWithProv={rw} readTotal={rt}");
        if w > 0 && rt > 0 {
            break (w, rt);
        }
        if std::time::Instant::now() >= deadline {
            check(false, "autosave llegó (written>0) dentro del timeout de 8 min");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };
    check(read_total > 0, &format!("inyección READ activa: parse() corrió ({read_total} chunks)"));
    check(
        written > 0,
        &format!("inyección WRITE activa: write()+Bridge(bootstrap) corrieron ({written} chunks)"),
    );

    // Reposo del mundo + cierre LIMPIO (no matar a media carga: eso atasca el
    // apagado de MC → /F → corrupción). Con el churn calmado, WM_CLOSE guarda y
    // sale solo; el proceso muere → se libera el lock del RegionFile en Windows.
    quiesce(&mut s, &mut reader, &mut id);
    kill_pid(pid_a);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    // Scan en disco con A ya muerto (evita el lock de RegionFile en Windows).
    // Reintentos por si el flush va con retraso.
    println!("[ledger] buscando mksa:prov en {}", region_dir.display());
    let mut persisted = false;
    for intento in 0..6 {
        if scan_region_for_prov(&region_dir) {
            persisted = true;
            break;
        }
        if intento < 5 {
            println!("       aún no aparece; reintento en 10s ({}/5)", intento + 1);
            std::thread::sleep(std::time::Duration::from_secs(10));
        }
    }
    check(persisted, "PERSISTENCIA: mksa:prov presente en el .mca tras el cierre limpio");

    // ── Lanzamiento B: read-back (roundtrip) ──
    println!("[ledger] lanzamiento B (read-back; los chunks de A ya están estampados)");
    let (pid_b, mut s2, mut reader2) = open_session(&handle, &games, instancia);

    let deadline_b = std::time::Instant::now() + std::time::Duration::from_secs(240);
    let mut id_b = 2u64;
    let read_with = loop {
        let (w, rw, rt) = ledger_stats(&mut s2, &mut reader2, id_b);
        id_b += 1;
        println!("       ledger.stats written={w} readWithProv={rw} readTotal={rt}");
        if rw > 0 {
            break rw;
        }
        if std::time::Instant::now() >= deadline_b {
            check(false, "readWithProv>0 dentro del timeout de 4 min (roundtrip)");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };
    check(
        read_with > 0,
        &format!("ROUNDTRIP: parse() detectó la sección escrita por A ({read_with} chunks)"),
    );

    quiesce(&mut s2, &mut reader2, &mut id_b);
    kill_pid(pid_b);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));
}

/// inc.5 Paso 2: época real + agregado de presencia. Un solo lanzamiento a
/// "mksa-test": confirma que state.epoch ya no es 0 y que el escaneo de palettes
/// puebla el agregado (wcg.counts) con bloques vanilla reales.
fn ledger2_check(instancia: &str) {
    println!("[ledger2] inc.5 Paso 2: época real + wcg.counts");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    // Época real (ya no el 0 del Paso 1).
    let ep = request(&mut s, &mut reader, 2, "state.epoch");
    let epoch = ep["ok"]["epoch"].as_i64().unwrap_or(0);
    let hash = ep["ok"]["hash"].as_str().unwrap_or("").to_string();
    check(
        epoch != 0 && !hash.is_empty(),
        &format!("state.epoch real: epoch={epoch} hash={hash}"),
    );

    // Agregado: esperar a que carguen chunks y contar bloques vanilla (garantizados
    // en cualquier terreno; un namespace de mod daría 0 salvo bloques modded colocados).
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    let mut id = 3u64;
    let (blocks, chunks) = loop {
        let r = request_p(&mut s, &mut reader, id, "wcg.counts",
            serde_json::json!({ "ns": "minecraft" }));
        id += 1;
        let b = r["ok"]["blocks"].as_i64().unwrap_or(0);
        let c = r["ok"]["chunks"].as_i64().unwrap_or(0);
        println!("       wcg.counts minecraft: blocks={b} chunks={c}");
        if c > 0 {
            break (b, c);
        }
        if std::time::Instant::now() >= deadline {
            check(false, "wcg.counts{minecraft}.chunks>0 dentro del timeout de 2.5 min");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };
    check(chunks > 0, &format!("agregado: {chunks} chunks con bloques minecraft"));
    check(blocks > 0, &format!("palette scan + decode: {blocks} bloques minecraft contados"));

    // BAD_PARAMS sin ns.
    let bad = request_p(&mut s, &mut reader, id, "wcg.counts", serde_json::json!({}));
    check(bad["err"]["code"] == "BAD_PARAMS", "wcg.counts sin ns: BAD_PARAMS");

    quiesce(&mut s, &mut reader, &mut id);
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));
}

/// inc.5 Paso 3: ledger en vivo (de-risk). Un solo lanzamiento a "mksa-test".
/// Confirma que el NUEVO punto de inyección (LevelChunk.setBlockState) dispara
/// — las mutaciones vanilla del tick (fluidos, hierba, ticks aleatorios) hacen
/// setBlocks>0 — y que el índice de bloques modded se construyó (moddedBlocks>0,
/// los 16 mods añaden bloques), todo sin crashear. La agencia REAL (actor modded)
/// exige gameplay con un mod mutando bloques y se verifica por test manual: el
/// camino vanilla no produce entradas de agencia (actor minecraft).
fn ledger3_check(instancia: &str) {
    println!("[ledger3] inc.5 Paso 3: ledger en vivo (hook setBlockState + agencia)");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    // El índice de bloques modded se construye en buildBlockIndex tras wcg-ready
    // (antes de game-ready), así que ya debería estar al abrir la sesión.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    let mut id = 3u64;
    let (set_blocks, modded, agency) = loop {
        let r = request(&mut s, &mut reader, id, "ledger.stats");
        id += 1;
        if !r["ok"].is_object() {
            check(false, &format!("ledger.stats respondió ok (err={})", r["err"]));
        }
        let sb = r["ok"]["setBlocks"].as_i64().unwrap_or(0);
        let mb = r["ok"]["moddedBlocks"].as_i64().unwrap_or(0);
        let ag = r["ok"]["agencyEntries"].as_i64().unwrap_or(0);
        println!("       ledger.stats setBlocks={sb} moddedBlocks={mb} agencyEntries={ag}");
        // setBlocks>0 confirma el hook; moddedBlocks>0 confirma el índice. El mundo
        // tickeando dispara setBlockState en LevelChunk (post-gen) en segundos.
        if sb > 0 && mb > 0 {
            break (sb, mb, ag);
        }
        if std::time::Instant::now() >= deadline {
            check(false, "setBlocks>0 && moddedBlocks>0 dentro del timeout de 2.5 min");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };
    check(
        modded > 0,
        &format!("índice de bloques modded construido: {modded} bloques modded"),
    );
    check(
        set_blocks > 0,
        &format!("hook LevelChunk.setBlockState activo: {set_blocks} mutaciones capturadas"),
    );
    println!(
        "       agencyEntries={agency} (vanilla: 0 esperado; la agencia real exige un mod mutando bloques — test manual)"
    );

    // Regresión del Paso 2: la firma nueva de onChunkWrite sigue estampando época real.
    let ep = request(&mut s, &mut reader, id, "state.epoch");
    let epoch = ep["ok"]["epoch"].as_i64().unwrap_or(0);
    check(epoch != 0, &format!("regresión Paso 2: state.epoch real ({epoch})"));

    quiesce(&mut s, &mut reader, &mut id);
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));
}

// ─────────────────────────── In-process, corte 1: handle vivo + mutación ───────────────────────────

/// In-process corte 1 (proyecto §3.5): el de-risk del END-GOAL. A diferencia del
/// camino de relaunch (cortes 1–4b), que muta parcheando la (de)serialización SIN
/// handle vivo, esta ruta hace lo que el relaunch evitaba por diseño: el agente
/// **retiene un handle vivo** a MinecraftServer/ServerLevel y **marshaliza al hilo
/// del servidor** para mutar el mundo EN CALIENTE, sin reiniciar el proceso.
///
/// Un solo lanzamiento a "mksa-test". Llama `tx.probeSetBlock`, que en el hilo del
/// servidor hace un ciclo **reversible** derivado del mundo: ancla en el spawn, sube
/// hasta una celda de AIRE, coloca minecraft:stone, lo relee EN VIVO, y restaura el
/// estado original — verificando ambos extremos. Aire ⇒ sin riesgo de block entity y
/// sin suposición de contenido; el assert `restored==original` prueba que la mutación
/// deja el mundo idéntico. Veredicto: matched (vi stone) + restored (volvió a aire) +
/// onServerThread, y disco sin corrupción tras el cierre.
///
/// NO es aún el teardown Tier 1 (filtrado de registro + barrido en vivo del footprint
/// + reload de datapack); es la prueba de que las dos capacidades nuevas funcionan.
fn inproc1_check(instancia: &str) {
    println!("[inproc1] in-process corte 1: handle vivo + marshaling + mutación reversible (§3.5)");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    // El servidor integrado y el jugador aparecen poco DESPUÉS de game-ready (quickplay
    // carga el mundo). Sondear: NOT_READY mientras no estén listos. CLAVE: ningún
    // check() aquí dentro — un fallo NO debe abortar antes de cerrar el juego, o queda
    // huérfano. Capturamos el resultado y juzgamos DESPUÉS de kill_pid.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(180);
    let mut id = 3u64;
    let outcome: Result<serde_json::Value, String> = loop {
        let r = request(&mut s, &mut reader, id, "tx.probeSetBlock");
        id += 1;
        if r["ok"].is_object() {
            break Ok(r);
        }
        let code = r["err"]["code"].as_str().unwrap_or("?").to_string();
        let msg = r["err"]["msg"].as_str().unwrap_or("").to_string();
        println!("       tx.probeSetBlock aún no listo: {code} ({msg})");
        if code != "NOT_READY" {
            break Err(format!("tx.probeSetBlock falló: {code} — {msg}"));
        }
        if std::time::Instant::now() >= deadline {
            break Err("el servidor/jugador no estuvo listo dentro del timeout de 3 min".to_string());
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };

    // SIEMPRE cerrar el juego antes de juzgar (haya ido bien o mal): nunca dejar un
    // Minecraft huérfano corriendo.
    quiesce(&mut s, &mut reader, &mut id);
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    // Veredicto, ya con el juego cerrado: check() puede salir(1) sin dejar huérfanos.
    let r = match outcome {
        Ok(r) => r,
        Err(e) => {
            check(false, &e);
            unreachable!()
        }
    };
    let ok = &r["ok"];
    let pos = &ok["pos"];
    let original = ok["original"].as_str().unwrap_or("?");
    let after_set = ok["afterSet"].as_str().unwrap_or("?");
    let after_restore = ok["afterRestore"].as_str().unwrap_or("?");
    let thread = ok["thread"].as_str().unwrap_or("?");
    println!(
        "       pos={pos} anchor={} original={original} → set={} → afterSet={after_set} → afterRestore={after_restore} (hilo: {thread})",
        ok["anchor"], ok["set"]
    );

    check(
        ok["onServerThread"].as_bool().unwrap_or(false),
        &format!("la mutación corrió en el hilo del servidor ({thread})"),
    );
    check(
        ok["matched"].as_bool().unwrap_or(false),
        &format!("setBlock vivo aplicado: la celda leyó {after_set} (esperado minecraft:stone)"),
    );
    check(
        ok["restored"].as_bool().unwrap_or(false),
        &format!("reversibilidad verificada en vivo: la celda volvió a {after_restore} (== original {original})"),
    );
    println!("       in-process corte 1 OK: el agente mutó el mundo vivo sin reiniciar y lo dejó intacto");
}

// ─────────────────────────── Tier 1 corte 1: barrido in-process ───────────────────────────

/// Tier 1 corte 1 (in-process): el ledger orquesta una mutación in-process por
/// primera vez. El usuario coloca escaleras de `mcwstairs` en el mundo desechable;
/// el agente las localiza vía el agregado del ledger (chunks afectados), captura
/// su footprint al archivo de restauración (mismo formato MKSAR1 del corte 3 del
/// relaunch — captura simétrica vía save forzado, deuda técnica conocida del
/// corte) y las reemplaza por aire **en vivo**. Verifica por re-escaneo que
/// `residualBlocks == 0` (NO usa wcg.counts: el agregado queda obsoleto hasta el
/// próximo save — el agente lo señala con `aggregateStale:true`).
///
/// Alcance acotado: solo barrido + captura. NO filtra registro (corte 3 de Tier 1)
/// ni recarga datapack (corte 3 de Tier 1) ni re-inyecta (corte 4 de Tier 1). NO
/// audita disco — esa es la frontera del corte 4 del relaunch ya probada.
fn tier1_1_check(instancia: &str) {
    println!("[tier1-1] Tier 1 corte 1 (in-process): barrido vivo + captura simétrica");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let world = saves.join("mksa-test");

    const VICTIM: &str = "mcwstairs";

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    println!("\n   >>> COLOCA 3–5 escaleras de {VICTIM} en columnas distintas (cerca del spawn).");
    println!("   >>> Quédate quieto cuando termines. El harness ejecuta el barrido cuando detecte la huella.");
    println!("   >>> (esperando que coloques bloques — ledger.setBlocks deltas\n");
    let mut id = 3u64;
    // El wait NO puede colgarse de wcg.counts: ese agregado se popula al deserializar
    // chunks (parse), así que si la plantilla tiene un chunk fantasma con mcwstairs
    // de un test previo, wcg.counts>0 sale ANTES de que el usuario coloque nada y el
    // barrido luego itera el chunk fantasma (fuera del view distance) en vez de los
    // del jugador. Solución robusta: esperar a setBlocks (mutaciones in-vivo via
    // onSetBlock, atribuibles al usuario) crezca ≥3. Es independiente del estado de
    // la plantilla.
    let (_w0, _rp0, _rt0) = ledger_stats(&mut s, &mut reader, id);
    id += 1;
    let stats0_set = request(&mut s, &mut reader, id, "ledger.stats")["ok"]["setBlocks"]
        .as_i64().unwrap_or(0);
    id += 1;
    let placed_delta = wait_set_blocks_delta(&mut s, &mut reader, &mut id, stats0_set, 5, 300);
    check(
        placed_delta >= 5,
        &format!("usuario colocó ≥5 bloques (ledger.setBlocks Δ={placed_delta} sobre base={stats0_set})"),
    );
    // Tras alcanzar el delta, esperar 15s extra: setBlocks cuenta TODA mutación, no solo
    // mcwstairs. El gate Δ≥5 puede satisfacerse con piedra/dirt antes de que el usuario
    // termine de colocar las escaleras del mod. Este margen garantiza que el snapshot
    // que el dryRun toma incluye los bloques de la víctima. El sweep real corre después
    // y es la fuente de verdad del corte (no el dryRun).
    println!("       … margen 15s para que termines de colocar mcwstairs específicamente");
    std::thread::sleep(std::time::Duration::from_secs(15));

    // Restore file dentro del propio mundo (paralelo al corte 3 del relaunch).
    let restore_path = world.join("mksa").join("restore").join(format!(
        "tier1-1-{}.mksar",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0)
    ));
    let restore_path_str = restore_path.to_string_lossy().into_owned();

    // ── Pre-sweep en seco: confirmar que el descubrimiento estructural funciona
    //    y que la víctima es localizable, sin mutar el mundo. ──
    println!("[tier1-1] dryRun (sin mutación)");
    let dry = request_p(
        &mut s,
        &mut reader,
        id,
        "tx.tier1Sweep",
        serde_json::json!({ "ns": VICTIM, "dryRun": true }),
    );
    id += 1;
    let outcome_dry = capture_or_close(&dry, pid, &mut s, &mut reader, &mut id, instancia);
    let ok_dry = &outcome_dry["ok"];
    let would = ok_dry["wouldSweep"].as_i64().unwrap_or(0);
    let chunks_dry = ok_dry["chunksAffected"].as_i64().unwrap_or(0);
    println!(
        "       dryRun: chunksAffected={chunks_dry} wouldSweep={would} \
         staleAggregateChunks={} thread={}",
        ok_dry["staleAggregateChunks"], ok_dry["thread"]
    );
    println!(
        "       dryRun diag namespaceOf: nsNullSamples={} nsVanillaSamples={} \
         nsOtherModSamples={} nsOtherExample={:?} moddedBlockCount={}",
        ok_dry["nsNullSamples"], ok_dry["nsVanillaSamples"], ok_dry["nsOtherModSamples"],
        ok_dry["nsOtherExample"].as_str().unwrap_or(""), ok_dry["moddedBlockCount"]
    );
    println!(
        "       dryRun chunks del agregado: {} (lvls={}) nsHisto={}",
        ok_dry["chunkCoords"], ok_dry["levels"], ok_dry["nsHisto"]
    );
    // El dryRun es informativo, NO el veredicto del corte. Su agregado puede estar
    // stale por timing (autosave en mitad del wait) o por chunk fantasma de la
    // plantilla. La fuente de verdad es el sweep real (saveAllChunks forced=true +
    // arm + barrer). Aquí solo validamos que el descubrimiento estructural funciona.
    if would == 0 {
        println!("       ⚠ dryRun localizó wouldSweep=0 — agregado posiblemente stale; el sweep real es el gate");
    } else {
        println!("       ✔ dryRun localizó wouldSweep={would} bloques de {VICTIM} sin mutar el mundo");
    }
    check(chunks_dry >= 1, &format!("dryRun: al menos 1 chunk afectado ({chunks_dry})"));
    check(
        ok_dry["onServerThread"].as_bool().unwrap_or(false),
        "dryRun corrió en el hilo del servidor",
    );

    // ── Barrido real: captura + mutación + re-escaneo. ──
    println!("[tier1-1] sweep real: captura → save forzado → barrido → re-escaneo");
    println!("          restore file: {restore_path_str}");
    let sweep = request_p(
        &mut s,
        &mut reader,
        id,
        "tx.tier1Sweep",
        serde_json::json!({ "ns": VICTIM, "restorePath": &restore_path_str, "dryRun": false }),
    );
    id += 1;
    let outcome = capture_or_close(&sweep, pid, &mut s, &mut reader, &mut id, instancia);
    let ok = &outcome["ok"];
    let captured = ok["capturedBlocks"].as_i64().unwrap_or(0);
    let swept = ok["sweptBlocks"].as_i64().unwrap_or(0);
    let residual = ok["residualBlocks"].as_i64().unwrap_or(0);
    let chunks = ok["chunksAffected"].as_i64().unwrap_or(0);
    let stale_aggr = ok["staleAggregateChunks"].as_i64().unwrap_or(0);
    let sweep_errors = ok["sweepErrors"].as_i64().unwrap_or(0);
    let aggregate_stale = ok["aggregateStale"].as_bool().unwrap_or(false);
    let fallback = ok["levelFallbackTriggered"].as_i64().unwrap_or(0);
    let first_fb_dim = ok["firstFallbackDim"].as_str().unwrap_or("");
    println!(
        "       sweep: captured={captured} swept={swept} residual={residual} \
         chunksAffected={chunks} staleAggregate={stale_aggr} sweepErrors={sweep_errors} \
         aggregateStale={aggregate_stale} thread={}",
        ok["thread"]
    );
    println!(
        "       endurecimiento (tier1-cleanup): levelFallbackTriggered={fallback} \
         firstFallbackDim={first_fb_dim:?}"
    );
    println!(
        "       diag namespaceOf: nsNullSamples={} nsVanillaSamples={} nsOtherModSamples={} \
         nsOtherExample={:?} moddedBlockCount={} chunkCoords={} nsHisto={}",
        ok["nsNullSamples"], ok["nsVanillaSamples"], ok["nsOtherModSamples"],
        ok["nsOtherExample"].as_str().unwrap_or(""), ok["moddedBlockCount"],
        ok["chunkCoords"], ok["nsHisto"]
    );
    println!(
        "       diag SAVE: method={} writtenPre={} writtenPost={} Δ={}",
        ok["saveMethod"].as_str().unwrap_or("?"),
        ok["writtenPre"], ok["writtenPost"], ok["writtenDelta"]
    );
    println!(
        "       diag SCAN: calls={} ok={} fails={} armedNsHits={} lastError={:?}",
        ok["scanCalls"], ok["scanOk"], ok["scanFails"],
        ok["scanArmedNsHits"], ok["lastScanError"].as_str().unwrap_or("?")
    );
    println!(
        "       diag CAPTURE: calls={} palettesWithArmedNs={} dirtied={}",
        ok["captureCalls"], ok["capturePalettesWithArmedNs"], ok["dirtied"]
    );

    check(
        ok["onServerThread"].as_bool().unwrap_or(false),
        "el barrido corrió en el hilo del servidor",
    );
    check(captured > 0, &format!("captura simétrica: capturedBlocks={captured} > 0 (vía save forzado, deuda técnica)"));
    check(swept > 0, &format!("barrido en vivo: sweptBlocks={swept} > 0"));
    check(residual == 0, &format!("invariante post-sweep: residualBlocks=0 (medido {residual})"));
    check(chunks >= 1, &format!("chunksAffected={chunks} ≥ 1"));
    check(aggregate_stale, "aggregateStale=true (wcg.counts NO debe usarse para verificar el sweep)");
    check(sweep_errors == 0, &format!("sin errores de setBlock (sweepErrors={sweep_errors})"));

    // ── Cierre limpio: la captura ya ocurrió, no nos importa qué quede en disco ──
    quiesce(&mut s, &mut reader, &mut id);
    drop(reader);
    drop(s);
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    // ── Restore file: existe y tiene tamaño > cabecera ──
    // Cabecera MKSAR1: 7 bytes "MKSAR1\n" + 4 (ns len) + N (ns bytes) + 8 (epoch) → mínimo ~26 bytes.
    // Un registro 'B' añade 1 + 12 + 4 + nbtLen + 4 + beLen (≥1 sin BE). Cota inferior conservadora 40.
    let meta = std::fs::metadata(&restore_path);
    check(meta.is_ok(), &format!("restore file existe en disco ({restore_path_str})"));
    let size = meta.map(|m| m.len()).unwrap_or(0);
    check(size > 40, &format!("restore file tiene tamaño > cabecera ({size} bytes)"));

    println!("\n   ═══ VEREDICTO TIER 1 CORTE 1 ═══");
    println!("   El ledger orquesta una mutación in-process por PRIMERA VEZ: localizó el");
    println!("   footprint de {VICTIM} ({swept} bloques en {chunks} chunk(s)), capturó al MKSAR1");
    println!("   (simétrico al corte 3 del relaunch) y lo barrió en vivo sin reiniciar.");
    println!("   Deuda técnica conocida: la captura piggyback de saveAllChunks fuerza persistencia");
    println!("   global; la migración a captura quirúrgica (zero-IO) es la pieza 2 de la dirección");
    println!("   arquitectónica de Tier 1+ (Mundo vivo → Ledger, sin pasar por persistencia).\n");

    // Limpieza: el restore file de prueba va al historial para inspección manual.
    let history = world.join("mksa").join("restore").join("historial");
    if !history.exists() {
        let _ = std::fs::create_dir_all(&history);
    }
    if let Some(name) = restore_path.file_name() {
        let _ = std::fs::rename(&restore_path, history.join(name));
    }
}

// ─────────────────────── Tier 1 corte 3: filtro de registro vivo ───────────────────────
//
// El corte 2 cubrió "limpiar el pasado" (sweep). Sin filtro, el jugador planta una
// escalera al instante y la huella vuelve. El corte 3 cubre "vetar el futuro": una
// inyección al ENTRY de LevelChunk.setBlockState retorna null cuando el namespace
// del newState está armado. null es lo que MC mismo emite cuando prev==state, así
// que los callers (Level.setBlock, BlockItem.useOn, pistones, dispensadores) ya
// saben tratarlo — el packet no sale, el item no se consume, el jugador no ve nada.
//
// Coreografía: usuario coloca libremente (baseline), se arma veto, usuario intenta
// colocar de nuevo y NO debe poder, se desarma, vuelve a poder. La presencia del
// namespace en wcg.counts es el detector dirigido (no setBlocks, que cuenta toda
// mutación incluyendo gravity/ticks/light).
// ─────────────────────── Tier 1 corte 4: reload dirigido de datapacks ────────────────────
//
// Tercer pie de Tier 1 in-process tras sweep (corte 2) y filter (corte 3):
// el agente dispara MinecraftServer.reloadResources(currentPacks) programáticamente
// con el filtro armado. Los hooks del transformer en RecipeManager.prepare (return)
// y TagManagerLoader.build (entry) mutan in-place el payload del reload: las recetas
// y tags cuyo id tiene ns víctima se descartan ANTES de la fase apply, así nunca
// llegan al estado vivo. Patrón "filter, no des-registrar" (§3.1) llevado al datapack.
//
// La verificación es enteramente automática (sin interacción del usuario):
//   FASE A (snapshot): leer ledger.stats — esperar dp* counters en 0 (o consistentes
//                      con cargas previas si game-ready ya disparó un prepare).
//   FASE B (disparo): tx.tier1DpReload{namespaces:[mcwstairs]}. La llamada arma,
//                     dispara reloadResources, espera la CF (timeout 120s), desarma.
//   FASE C (aserto): el resultado de tier1DpReload trae deltas — recipesFiltered>0
//                    (mcwstairs añade ≥1 receta de stairs), recipeHookCalls>0
//                    (el hook corrió), tagHookCalls>0 (build de tags corrió).
//                    tagsFiltered puede ser 0 si mcwstairs no añade tags propios
//                    (los items pueden estar en tags vanilla, que NO filtramos —
//                    eso es cascade, F4/wcg.refs).
//
// No requiere mundo cargado: el reload es server-side y corre sobre el servidor
// integrado en cuanto game-ready emite (Boot.clientInstance presente). Sin user input.
fn tier1_5_check(instancia: &str) {
    println!("[tier1-5] Tier 1 corte 5 (in-process): envoltorio transaccional con TxReceipt");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let world = saves.join("mksa-test");

    const VICTIM: &str = "mcwstairs";

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    let mut id = 3u64;
    println!("       esperando a que el mundo termine de cargar (setBlocks estable 6s)...");
    let stable = wait_setblocks_quiescent(&mut s, &mut reader, &mut id, 6, 120);
    println!("       mundo cargado (setBlocks={stable} estable).\n");

    // ── Baseline del bloque fantasma de la plantilla (deuda del harness, corte 5).
    //    mksa-test-template trae una huella pre-horneada de mcwstairs; al primer
    //    save scan() la vuelca en byChunk, así que wcg.counts/snapshot la incluyen
    //    aunque el usuario no haya colocado nada. Medirla ANTES de que coloque aísla
    //    "lo que el usuario puso esta sesión" de "lo que ya venía en la plantilla":
    //    sin esto, captured (lo que el sweep saca) vs snapshot.blocks (lo que byChunk
    //    cuenta) quedaba como un misterio sin explicar.
    let phantom_baseline = request_p(&mut s, &mut reader, id, "wcg.counts",
        serde_json::json!({ "ns": VICTIM }))["ok"]["blocks"].as_i64().unwrap_or(0);
    id += 1;
    println!("       baseline fantasma de plantilla: wcg.counts.{VICTIM}.blocks = {phantom_baseline}\n");

    // ── Footprint: el usuario coloca para garantizar snapshot.blocks > 0.
    println!("   >>> COLOCA 3–5 escaleras de {VICTIM} cerca del spawn.");
    println!("   >>> Quédate quieto cuando termines. El harness ejecuta la tx cuando detecte la huella.\n");
    let base_set = request(&mut s, &mut reader, id, "ledger.stats")["ok"]["setBlocks"]
        .as_i64().unwrap_or(0);
    id += 1;
    let placed_delta = wait_set_blocks_delta(&mut s, &mut reader, &mut id, base_set, 3, 300);
    check(
        placed_delta >= 3,
        &format!("usuario colocó ≥3 bloques (setBlocks Δ={placed_delta} sobre base={base_set})"),
    );
    println!("       … margen 15s para que termines de colocar mcwstairs específicamente");
    std::thread::sleep(std::time::Duration::from_secs(15));

    // ── Snapshot ANTES del disable (gate del corte: debe haber huella real).
    let pre_counts = request_p(&mut s, &mut reader, id, "wcg.counts",
        serde_json::json!({ "ns": VICTIM }));
    id += 1;
    let pre_blocks = pre_counts["ok"]["blocks"].as_i64().unwrap_or(0);
    let pre_chunks = pre_counts["ok"]["chunks"].as_i64().unwrap_or(0);
    println!("       PRE-disable: wcg.counts.{VICTIM} = blocks={pre_blocks} chunks={pre_chunks}");
    check(pre_blocks > 0, &format!("PRE: huella de {VICTIM} > 0 (blocks={pre_blocks})"));

    let restore_path = world.join("mksa").join("restore").join(format!(
        "tier1-5-{}.mksar",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0)
    ));
    let _ = std::fs::create_dir_all(restore_path.parent().unwrap());
    let restore_path_str = restore_path.to_string_lossy().into_owned();

    // ── FASE A: tx.tier1Disable — UNA llamada orquesta todo.
    println!("\n[tier1-5] tx.tier1Disable {{ ns: \"{VICTIM}\", restorePath: ... }}");
    let dis = request_p(&mut s, &mut reader, id, "tx.tier1Disable",
        serde_json::json!({ "ns": VICTIM, "restorePath": &restore_path_str }));
    id += 1;
    check(dis["ok"].is_object(),
        &format!("tx.tier1Disable completó (err={:?})", dis.get("err")));

    let r = &dis["ok"];
    let tx_id = r["txId"].as_i64().unwrap_or(-1);
    let status = r["status"].as_str().unwrap_or("?");
    let duration = r["durationMs"].as_i64().unwrap_or(-1);
    let epoch_str = r["epoch"].as_str().unwrap_or("?");
    let snap_blocks = r["snapshot"]["blocks"].as_i64().unwrap_or(-1);
    let snap_chunks = r["snapshot"]["chunks"].as_i64().unwrap_or(-1);
    let snap_items = r["snapshot"]["items"].as_i64().unwrap_or(-1);
    let receipt_restore = r["restorePath"].as_str().unwrap_or("");

    println!("       txId={tx_id} status={status} durationMs={duration}");
    println!("       epoch={epoch_str}");
    println!("       snapshot: blocks={snap_blocks} chunks={snap_chunks} items={snap_items}");
    println!("       restorePath={receipt_restore}");

    check(tx_id >= 1, &format!("txId monótono (txId={tx_id})"));
    check(status == "OK", &format!("status agregado = OK (got '{status}')"));
    check(duration > 0, &format!("durationMs > 0 ({duration})"));
    check(epoch_str.len() == 16, &format!("epoch es hex de 16 chars ({epoch_str})"));
    check(snap_blocks == pre_blocks,
        &format!("snapshot.blocks ({snap_blocks}) coincide con wcg.counts pre-disable ({pre_blocks})"));
    check(snap_chunks == pre_chunks,
        &format!("snapshot.chunks ({snap_chunks}) coincide con wcg.counts pre-disable ({pre_chunks})"));
    check(snap_items == 0, "snapshot.items = 0 (playerdata scan aún no enganchado en T1)");
    check(receipt_restore == restore_path_str,
        "receipt.restorePath promovido a nivel raíz coincide con el solicitado");

    // Fix race byChunk (corte 5, junio 2026): el snapshot incluye chunkKeys
    // ATÓMICAMENTE en una sola pasada sobre byChunk, y el sweep los recibe
    // explícitamente (no relee byChunk en el server thread). Sin estas dos
    // afirmaciones el corte sería intermitente — 3/6 corridas fallaban con
    // sweep ~30ms / captured=0 cuando un autosave reescribía byChunk entre
    // el snapshot (IPC) y el sweep (server thread).
    let snap_keys = r["snapshot"]["chunkKeys"].as_array()
        .map(|a| a.len()).unwrap_or(0);
    println!("       snapshot.chunkKeys: {} entries ({:?})",
        snap_keys, r["snapshot"]["chunkKeys"]);
    check(snap_keys > 0,
        &format!("snapshot.chunkKeys no vacío ({snap_keys}) — la lista atómica fuente del sweep"));
    check(snap_keys as i64 == snap_chunks,
        &format!("snapshot.chunkKeys.len == snapshot.chunks ({snap_keys}=={snap_chunks})"));

    // ── Pasos en orden esperado.
    let steps = r["steps"].as_array().expect("steps array");
    let names: Vec<&str> = steps.iter().map(|s| s["name"].as_str().unwrap_or("?")).collect();
    println!("       steps ({}): {:?}", steps.len(), names);
    check(steps.len() == 4, &format!("steps tiene 4 elementos ({})", steps.len()));
    check(names == ["vetoArm", "snapshot", "sweep", "dpReload"],
        &format!("orden de pasos = [vetoArm, snapshot, sweep, dpReload] (got {names:?})"));

    for step in steps {
        let n = step["name"].as_str().unwrap_or("?");
        let st = step["status"].as_str().unwrap_or("?");
        let ms = step["ms"].as_i64().unwrap_or(-1);
        check(st == "OK", &format!("step '{n}' status=OK (got '{st}')"));
        check(ms >= 0, &format!("step '{n}' ms >= 0 ({ms})"));
        println!("         · {n:9} {st:6} {ms:>5}ms  detail={}", step["detail"]);
    }

    let sweep_detail = &steps[2]["detail"];
    let captured = sweep_detail["captured"].as_i64().unwrap_or(-1);
    let swept = sweep_detail["swept"].as_i64().unwrap_or(-1);
    let residual = sweep_detail["residual"].as_i64().unwrap_or(-1);
    let chunk_keys_source = sweep_detail["chunkKeysSource"].as_str().unwrap_or("?");
    check(captured > 0, &format!("sweep.captured > 0 ({captured})"));
    check(swept == captured, &format!("sweep.swept == captured ({swept}=={captured})"));
    check(residual == 0, &format!("sweep.residual = 0 ({residual})"));
    check(chunk_keys_source == "snapshot",
        &format!("sweep usó la lista atómica del snapshot, no relectura en vivo (chunkKeysSource='{chunk_keys_source}')"));

    // ── Deuda del harness saldada (corte 5): captured-vs-snapshot ya no es misterio.
    //    snapshot.blocks lee byChunk → incluye el fantasma de la plantilla; captured
    //    es lo que el sweep realmente sacó. La huella que el usuario colocó esta
    //    sesión = snapshot.blocks − phantom_baseline. El bracket asertado se cumple
    //    en ambos mundos posibles (fantasma en chunk cargado → también barrido, o
    //    fantasma fuera del footprint barrido), y la línea de diagnóstico dice cuál
    //    ocurrió, de modo que la corrida REVELA el comportamiento en vez de ocultarlo.
    let session_footprint = snap_blocks - phantom_baseline;
    println!("       captura vs snapshot: phantom={phantom_baseline} snapshot={snap_blocks} captured={captured} → huella de sesión={session_footprint}");
    check(captured >= session_footprint && captured <= snap_blocks,
        &format!("captured ({captured}) ∈ [huella de sesión {session_footprint}, snapshot {snap_blocks}] — el fantasma de plantilla explica la diferencia (deuda del harness saldada)"));
    if phantom_baseline == 0 {
        println!("       … sin fantasma de plantilla esta corrida (template limpio): captured == snapshot == huella de sesión");
    } else if captured == snap_blocks {
        println!("       … el fantasma de plantilla está en chunk cargado y TAMBIÉN fue barrido: captured == snapshot");
    } else if captured == session_footprint {
        println!("       … el fantasma vive fuera del footprint barrido: captured == huella de sesión ({session_footprint})");
    } else {
        println!("       … barrido parcial del fantasma: captured entre huella de sesión y snapshot");
    }

    // Breadcrumbs del fix force-dirty real (corte 5, junio 2026). El bug
    // intermitente (3/6 fallos, ~30ms vs ~1200ms) era setBlock(currentState)
    // que NO marca dirty (MC corta si prev==newState antes de markUnsaved).
    // El nuevo force-dirty hace setBlock(diff)+setBlock(orig) sobre pos
    // vanilla — mutación neta cero, dos markUnsaved reales.
    let fd_attempts = sweep_detail["forceDirtyAttempts"].as_i64().unwrap_or(-1);
    let fd_ok = sweep_detail["forceDirtyOk"].as_i64().unwrap_or(-1);
    let fd_failed = sweep_detail["forceDirtyFailed"].as_i64().unwrap_or(-1);
    let fd_last_y = sweep_detail["forceDirtyLastY"].as_i64().unwrap_or(-99);
    println!("       force-dirty: attempts={fd_attempts} ok={fd_ok} failed={fd_failed} lastY={fd_last_y}");
    if fd_attempts == 0 {
        println!("       … chunk dirty desde la colocación del usuario (caso normal, no se necesitó forzar)");
    } else {
        check(fd_ok > 0 && fd_failed == 0,
            &format!("force-dirty real funcionó (attempts={fd_attempts} ok={fd_ok} failed={fd_failed}) — el bug intermitente del corte 5 está cerrado"));
    }

    // sweepTrace: serie literal de eventos del for-loop del sweep. Cubre cada
    // paso desde "loop:enter" hasta "write:return" por chunk × nivel, más cualquier
    // excepción capturada. Imprimirlo literal en cada corrida hace visible el bug
    // sin asumir hipótesis (si el sweep sale ANTES de copyOf:call, lo verás aquí).
    if let Some(trace) = sweep_detail["sweepTrace"].as_array() {
        println!("       sweepTrace ({} eventos):", trace.len());
        for evt in trace {
            if let Some(s) = evt.as_str() {
                println!("         > {s}");
            }
        }
    } else {
        println!("       sweepTrace: <no presente>");
    }

    let dp_detail = &steps[3]["detail"];
    let recipes_filtered = dp_detail["recipesFiltered"].as_i64().unwrap_or(-1);
    let recipe_calls = dp_detail["recipeHookCalls"].as_i64().unwrap_or(-1);
    check(recipes_filtered > 0, &format!("dpReload.recipesFiltered > 0 ({recipes_filtered})"));
    check(recipe_calls > 0, &format!("dpReload.recipeHookCalls > 0 ({recipe_calls})"));

    // ── Side-effects POST-disable.
    let post_counts = request_p(&mut s, &mut reader, id, "wcg.counts",
        serde_json::json!({ "ns": VICTIM }));
    id += 1;
    let post_blocks = post_counts["ok"]["blocks"].as_i64().unwrap_or(-1);
    let post_stats = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    let post_veto = post_stats["ok"]["vetoArmed"].as_i64().unwrap_or(-1);
    println!("       POST-disable: wcg.counts.{VICTIM}.blocks={post_blocks} ledger.vetoArmed={post_veto}");
    check(post_blocks == 0, &format!("POST: la huella fue barrida (blocks={post_blocks})"));
    check(post_veto > 0, &format!("POST: veto armado tras disable (vetoArmed={post_veto})"));
    check(std::path::Path::new(&restore_path_str).exists(),
        &format!("restore file existe en disco ({restore_path_str})"));
    check(r["error"].is_null(), "receipt.error = null en OK");
    check(r["rollback"].is_null(), "receipt.rollback = null en OK");

    // ── tx.history contiene el receipt del disable.
    let hist1 = request(&mut s, &mut reader, id, "tx.history"); id += 1;
    let hist1_count = hist1["ok"]["count"].as_i64().unwrap_or(-1);
    println!("       tx.history: count={hist1_count}");
    check(hist1_count >= 1, &format!("history tiene ≥1 entry post-disable ({hist1_count})"));
    let hist1_items = hist1["ok"]["items"].as_array().expect("items");
    let last = &hist1_items[hist1_items.len() - 1];
    check(last["txId"].as_i64().unwrap_or(-1) == tx_id,
        "el último item del history es el receipt del disable");
    check(last["op"].as_str().unwrap_or("?") == "disable", "last.op = disable");

    // ── FASE A-bis: STRESS-TEST instrumentado — ¿qué borra las colocaciones hechas
    //    DESPUÉS del barrido? Era la única observación del corte 5 sin cerrar.
    //    Hipótesis en juego:
    //      (1) el veto del corte 3 las descarta en setBlockState vía null → fantasmas
    //          de cliente hasta el re-sync;
    //      (2) la "migración de época" en el autosave las recoge.
    //    La (2) está REFUTADA por código: el epoch es un hash FNV1a64 del set de mods
    //    (Ledger.epochHash), se estampa en la sección prov del chunk y en los restore
    //    files, pero ninguna ruta borra bloques por época. Aquí la (1) se confirma
    //    EMPÍRICAMENTE: el veto sigue armado tras el disable (post_veto>0 ya asertado),
    //    así que una colocación de la víctima incrementa vetoHits (entry de
    //    setBlockState) pero NO byChunkInc/setBlocks (el observador del ARETURN no
    //    corre tras el veto). Si byChunkInc Δ=0 y wcg.counts sigue en 0 mientras
    //    vetoHits sube, las colocaciones NUNCA fueron estado de servidor → no hay nada
    //    que un autosave pueda migrar; su desaparición visual es corrección de fantasma
    //    del cliente.
    println!("\n[tier1-5] FASE A-bis: stress-test instrumentado (el veto sigue armado del disable)");
    let st0 = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    let veto_base = st0["ok"]["vetoHits"].as_i64().unwrap_or(0);
    let inc_base = st0["ok"]["byChunkInc"].as_i64().unwrap_or(0);
    let setb_base = st0["ok"]["setBlocks"].as_i64().unwrap_or(0);
    println!("       baseline: vetoHits={veto_base} byChunkInc={inc_base} setBlocks={setb_base}");
    println!("   >>> COLOCA 3+ escaleras de {VICTIM} AHORA (el veto está armado).");
    println!("   >>> Las verás aparecer y, al rato, desaparecer. Quédate quieto al terminar.\n");

    // Detectamos los intentos vía vetoHits, NO setBlocks: bajo veto setBlocks no se
    // incrementa, así que wait_set_blocks_delta colgaría. El propio uso de
    // wait_veto_hits_delta es ya media prueba — si las colocaciones llegaran a
    // estado de servidor, este contador no se movería.
    // wait_veto_hits_delta devuelve el DELTA (no el absoluto), igual que en --tier1-3.
    let veto_dh = wait_veto_hits_delta(&mut s, &mut reader, &mut id, veto_base, 3, 300);
    check(veto_dh >= 3,
        &format!("colocaciones post-sweep vetadas en setBlockState (vetoHits Δ={veto_dh} ≥ 3)"));

    // Ventana para que el cliente re-sincronice y corrija los fantasmas (un autosave
    // o un block update vecino dispara el resync del chunk). Si la hipótesis (2) fuese
    // cierta, AQUÍ los bloques reales serían "migrados"; pero no hay bloques reales.
    println!("       … 20s para que el cliente re-sincronice y borre los fantasmas");
    std::thread::sleep(std::time::Duration::from_secs(20));

    let st1 = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    let inc_dh = st1["ok"]["byChunkInc"].as_i64().unwrap_or(0) - inc_base;
    let setb_dh = st1["ok"]["setBlocks"].as_i64().unwrap_or(0) - setb_base;
    let stress_counts = request_p(&mut s, &mut reader, id, "wcg.counts",
        serde_json::json!({ "ns": VICTIM })); id += 1;
    let stress_blocks = stress_counts["ok"]["blocks"].as_i64().unwrap_or(-1);
    println!("       tras stress+resync: byChunkInc Δ={inc_dh} setBlocks Δ={setb_dh} wcg.counts.{VICTIM}.blocks={stress_blocks}");

    check(inc_dh == 0,
        &format!("las colocaciones post-sweep NUNCA llegaron a estado de servidor (byChunkInc Δ={inc_dh}, esperado 0)"));
    check(stress_blocks == 0,
        &format!("wcg.counts sigue en 0 tras el stress (blocks={stress_blocks}) — no hubo bloques reales que un autosave pudiera migrar"));
    println!("       DIAGNÓSTICO (observación del corte 5, ahora cerrada): las colocaciones");
    println!("       post-barrido se vetan vía null en setBlockState (vetoHits Δ={veto_dh}) y");
    println!("       jamás son estado de servidor (byChunkInc Δ=0, wcg.counts=0). Su");
    println!("       desaparición es corrección de fantasma del cliente, NO migración de");
    println!("       época en autosave (mecanismo inexistente: epoch es un hash). El binomio");
    println!("       sweep+veto cierra el pasado y el futuro de la víctima.");

    // ── FASE B: tx.tier1Enable — desarma veto + restitución viva + dpReload sin filtro.
    println!("\n[tier1-5] tx.tier1Enable {{ ns: \"{VICTIM}\", restorePath: \"…\" }}");
    let ena = request_p(&mut s, &mut reader, id, "tx.tier1Enable",
        serde_json::json!({ "ns": VICTIM, "restorePath": &restore_path_str }));
    id += 1;
    check(ena["ok"].is_object(),
        &format!("tx.tier1Enable completó (err={:?})", ena.get("err")));

    let e = &ena["ok"];
    let ena_tx_id = e["txId"].as_i64().unwrap_or(-1);
    let ena_status = e["status"].as_str().unwrap_or("?");
    let ena_op = e["op"].as_str().unwrap_or("?");
    let ena_steps = e["steps"].as_array().expect("steps array");
    let ena_names: Vec<&str> = ena_steps.iter().map(|s| s["name"].as_str().unwrap_or("?")).collect();
    println!("       txId={ena_tx_id} status={ena_status} op={ena_op}");
    println!("       steps ({}): {:?}", ena_steps.len(), ena_names);

    check(ena_tx_id > tx_id, &format!("enable.txId > disable.txId ({ena_tx_id} > {tx_id})"));
    check(ena_op == "enable", &format!("enable.op = enable (got '{ena_op}')"));
    check(ena_status == "OK", &format!("enable.status = OK (got '{ena_status}')"));
    check(ena_names == ["vetoDisarm", "restitution", "dpReload"],
        &format!("orden de pasos = [vetoDisarm, restitution, dpReload] (got {ena_names:?})"));
    for step in ena_steps {
        let n = step["name"].as_str().unwrap_or("?");
        let st = step["status"].as_str().unwrap_or("?");
        let ms = step["ms"].as_i64().unwrap_or(-1);
        check(st == "OK", &format!("enable step '{n}' status=OK"));
        println!("         · {n:11} {st:6} {ms:>5}ms  detail={}", step["detail"]);
    }
    let dp_clean = &ena_steps[2]["detail"];
    check(dp_clean["filtered"].as_bool() == Some(false),
        "enable.dpReload.filtered = false (clean reload, sin filtro armado)");

    // ── Núcleo del corte 5b: la restitución viva re-inyectó los bloques capturados.
    let rest = &ena_steps[1]["detail"];
    let rest_captured = rest["captured"].as_i64().unwrap_or(-1);
    let rest_restored = rest["restored"].as_i64().unwrap_or(-1);
    let rest_conflicts = rest["conflicts"].as_i64().unwrap_or(-1);
    let rest_misses = rest["misses"].as_i64().unwrap_or(-1);
    let rest_errors = rest["restitutionErrors"].as_i64().unwrap_or(-1);
    println!("       restitución: captured={rest_captured} restored={rest_restored} \
        conflicts={rest_conflicts} misses={rest_misses} errors={rest_errors}");
    if let Some(tr) = rest["restTrace"].as_array() {
        if !tr.is_empty() {
            println!("       restTrace ({}):", tr.len());
            for ev in tr.iter().take(20) { println!("         · {}", ev.as_str().unwrap_or("?")); }
        }
    }
    // Todas las celdas barridas siguen en aire al hacer enable (garantizado por
    // FASE A-bis: byChunkInc Δ==0 → las colocaciones post-sweep nunca fueron estado
    // de servidor, así que la única ocupación posible de esas celdas es la víctima
    // original, ya barrida a aire). Por tanto restored debe igualar captured.
    check(rest_captured > 0, &format!("restitución capturó bloques del archivo (captured={rest_captured} > 0)"));
    check(rest_restored == rest_captured,
        &format!("restitución re-inyectó TODO (restored={rest_restored} == captured={rest_captured})"));
    check(rest_conflicts == 0,
        &format!("ningún conflicto §7 (conflicts={rest_conflicts} == 0 — las celdas barridas seguían en aire)"));
    check(rest_misses == 0,
        &format!("ninguna reconstrucción NBT→BlockState falló (misses={rest_misses} == 0)"));
    check(rest_errors == 0,
        &format!("ninguna excepción de inyección (restitutionErrors={rest_errors} == 0)"));

    // ── Side-effects POST-enable.
    let post2 = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    let post2_veto = post2["ok"]["vetoArmed"].as_i64().unwrap_or(-1);
    println!("       POST-enable: ledger.vetoArmed={post2_veto}");
    check(post2_veto == 0, &format!("POST-enable: veto desarmado (vetoArmed={post2_veto})"));

    let hist2 = request(&mut s, &mut reader, id, "tx.history"); id += 1;
    let hist2_count = hist2["ok"]["count"].as_i64().unwrap_or(-1);
    check(hist2_count == hist1_count + 1,
        &format!("history creció en 1 tras enable ({hist1_count} → {hist2_count})"));

    // ── Reaparición viva (corte 5b): los bloques volvieron al mundo en vivo. El
    //    setBlock de la restitución disparó onSetBlock (corte 2) → byChunk subió →
    //    wcg.counts lo refleja. Es la prueba numérica de la re-inyección real.
    let post3 = request_p(&mut s, &mut reader, id, "wcg.counts",
        serde_json::json!({ "ns": VICTIM }));
    id += 1;
    let post3_blocks = post3["ok"]["blocks"].as_i64().unwrap_or(-1);
    println!("       reaparición viva: wcg.counts.{VICTIM}.blocks tras enable = {post3_blocks} (esperado == restored={rest_restored})");
    check(post3_blocks == rest_restored,
        &format!("enable RESTITUYÓ bloques al mundo vivo (wcg.counts.blocks={post3_blocks} == restored={rest_restored})"));

    // ── Cierre.
    println!("\n  … corte 5b completado; cierro juego");
    drop(reader);
    drop(s);
    std::thread::sleep(std::time::Duration::from_secs(2));
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    println!("\n   ═══ VEREDICTO TIER 1 CORTE 5b ═══");
    println!("   Round-trip in-process SIMÉTRICO cerrado: tx.tier1Disable quita el");
    println!("   pasado (sweep) y veta el futuro (veto); tx.tier1Enable ahora DEVUELVE");
    println!("   los bloques al mundo vivo sin reiniciar. La restitución reconstruye");
    println!("   cada BlockState desde el NBT capturado (NbtUtils.readBlockState,");
    println!("   resuelto por forma + round-trip self-test) y lo re-inyecta vía");
    println!("   setBlock en el hilo del servidor, solo si la celda sigue en aire (§7).");
    println!("   Pasos del enable: [vetoDisarm, restitution, dpReload]. El setBlock");
    println!("   vivo dispara onSetBlock (corte 2) → byChunk sube → wcg.counts==restored");
    println!("   es la prueba numérica de que los bloques reaparecieron (no no-op).");
    println!("   PRIMER write-back in-process que reconstruye contenido (el sweep solo");
    println!("   escribía AIR): de-risk núcleo del §3.5 para todo lo que está por");
    println!("   encima de Tier 1. Alcance: estados de bloque (igual que corte 4);");
    println!("   contenido de block-entity e items del jugador quedan fuera (límite");
    println!("   documentado). La reversibilidad del DATO sigue cubierta por relaunch.");
}

fn tier1_4_check(instancia: &str) {
    println!("[tier1-4] Tier 1 corte 4 (in-process): reload dirigido de datapacks");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    const VICTIM: &str = "mcwstairs";

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    let mut id = 3u64;
    // Tras game-ready el servidor integrado vive y el reload inicial corrió ya. Damos
    // unos segundos extra para que cualquier autosave/init quede asentado antes de
    // pedir el reload programático.
    std::thread::sleep(std::time::Duration::from_secs(5));

    // ── FASE A: snapshot.
    let stats_a = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    let dp_armed_a = stats_a["ok"]["dpArmed"].as_i64().unwrap_or(-1);
    let dp_filt_rec_a = stats_a["ok"]["dpFilteredRecipes"].as_i64().unwrap_or(-1);
    let dp_filt_tag_a = stats_a["ok"]["dpFilteredTags"].as_i64().unwrap_or(-1);
    let dp_rec_calls_a = stats_a["ok"]["dpRecipeHookCalls"].as_i64().unwrap_or(-1);
    let dp_tag_calls_a = stats_a["ok"]["dpTagHookCalls"].as_i64().unwrap_or(-1);
    println!(
        "       PRE: dpArmed={dp_armed_a} dpFilteredRecipes={dp_filt_rec_a} \
         dpFilteredTags={dp_filt_tag_a} dpRecipeHookCalls={dp_rec_calls_a} \
         dpTagHookCalls={dp_tag_calls_a}"
    );
    check(dp_armed_a == 0, "PRE: ningún filtro de datapack armado");
    check(dp_filt_rec_a == 0, "PRE: dpFilteredRecipes=0 (filtro aún sin disparar)");
    check(dp_filt_tag_a == 0, "PRE: dpFilteredTags=0 (filtro aún sin disparar)");

    // ── FASE B: disparar el reload programático con el filtro armado.
    println!("[tier1-4] disparando tx.tier1DpReload para [{VICTIM}] (programático, sin tocar mods/)");
    let reload = request_p(&mut s, &mut reader, id, "tx.tier1DpReload",
        serde_json::json!({ "namespaces": [VICTIM] }));
    id += 1;
    check(reload["ok"].is_object(),
        &format!("tx.tier1DpReload completó (err={:?})", reload.get("err")));

    let recipes_filtered = reload["ok"]["recipesFiltered"].as_i64().unwrap_or(-1);
    let tags_filtered = reload["ok"]["tagsFiltered"].as_i64().unwrap_or(-1);
    let recipe_calls = reload["ok"]["recipeHookCalls"].as_i64().unwrap_or(-1);
    let tag_calls = reload["ok"]["tagHookCalls"].as_i64().unwrap_or(-1);
    let duration_ms = reload["ok"]["durationMs"].as_i64().unwrap_or(-1);
    let handles_sig = reload["ok"]["handles"].as_str().unwrap_or("?");
    let payload_class = reload["ok"]["recipePayloadClass"].as_str().unwrap_or("?");
    let last_err = reload["ok"]["dpLastError"].as_str().unwrap_or("?");
    let packs_count = reload["ok"]["packs"].as_array().map(|a| a.len()).unwrap_or(0);

    println!("       handles: {handles_sig}");
    println!("       recipePayloadClass: {payload_class}");
    println!(
        "       reload OK ({duration_ms} ms, {packs_count} packs): \
         recipesFiltered={recipes_filtered} tagsFiltered={tags_filtered} \
         recipeHookCalls={recipe_calls} tagHookCalls={tag_calls} \
         dpLastError={last_err}"
    );

    // ── FASE C: aserciones.
    check(packs_count > 0, &format!("selectedIds() devolvió ≥1 pack ({packs_count})"));
    check(handles_sig.contains("reload=MinecraftServer.")
            && handles_sig.contains("repo=")
            && handles_sig.contains("selectedIds="),
        "los tres handles se resolvieron por forma");
    check(recipe_calls > 0,
        &format!("el hook de RecipeManager.prepare corrió (Δ={recipe_calls})"));
    check(tag_calls > 0,
        &format!("el hook de TagManagerLoader.build corrió (Δ={tag_calls})"));
    check(recipes_filtered > 0,
        &format!("se filtraron recetas de {VICTIM} (Δ={recipes_filtered})"));
    if tags_filtered > 0 {
        println!("  ✔ se filtraron tags de {VICTIM} (Δ={tags_filtered})");
    } else {
        println!("  ◎ tagsFiltered=0 — {VICTIM} no añade tags propios (frontera v1; sus items \
                  pueden estar en tags vanilla, fuera de alcance — F4/wcg.refs).");
    }

    // Consistencia con ledger.stats (los deltas deben coincidir con las cifras post).
    let stats_b = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    let dp_filt_rec_b = stats_b["ok"]["dpFilteredRecipes"].as_i64().unwrap_or(-1);
    let dp_filt_tag_b = stats_b["ok"]["dpFilteredTags"].as_i64().unwrap_or(-1);
    let dp_rec_calls_b = stats_b["ok"]["dpRecipeHookCalls"].as_i64().unwrap_or(-1);
    let dp_tag_calls_b = stats_b["ok"]["dpTagHookCalls"].as_i64().unwrap_or(-1);
    let dp_armed_b = stats_b["ok"]["dpArmed"].as_i64().unwrap_or(-1);
    println!(
        "       POST: dpArmed={dp_armed_b} dpFilteredRecipes={dp_filt_rec_b} \
         dpFilteredTags={dp_filt_tag_b} dpRecipeHookCalls={dp_rec_calls_b} \
         dpTagHookCalls={dp_tag_calls_b}"
    );
    check(dp_armed_b == 0, "POST: filtro de datapack desarmado (defensivo) tras la operación");
    check(dp_filt_rec_b - dp_filt_rec_a == recipes_filtered,
        "ledger.stats.dpFilteredRecipes coincide con el delta reportado por tier1DpReload");
    check(dp_filt_tag_b - dp_filt_tag_a == tags_filtered,
        "ledger.stats.dpFilteredTags coincide con el delta reportado por tier1DpReload");

    // ── Segunda invocación del filtro: idempotencia / no-double-arm.
    println!("[tier1-4] segunda invocación (idempotencia)");
    let reload2 = request_p(&mut s, &mut reader, id, "tx.tier1DpReload",
        serde_json::json!({ "namespaces": [VICTIM] }));
    id += 1;
    check(reload2["ok"].is_object(), "tx.tier1DpReload 2ª vez completó");
    let recipes_filtered_2 = reload2["ok"]["recipesFiltered"].as_i64().unwrap_or(-1);
    println!("       2ª invocación: recipesFiltered={recipes_filtered_2} (esperado ≈ {recipes_filtered})");
    check(recipes_filtered_2 == recipes_filtered,
        "2ª invocación filtra la misma cantidad — el mecanismo es repetible y estable");

    // ── Cierre.
    println!("  … corte 4 completado; cierro juego");
    drop(reader);
    drop(s);
    std::thread::sleep(std::time::Duration::from_secs(2));
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    println!("\n   ═══ VEREDICTO TIER 1 CORTE 4 ═══");
    println!("   Reload dirigido de datapacks: el agente dispara reloadResources");
    println!("   programáticamente; los hooks al entry de TagManagerLoader.build y al");
    println!("   return de RecipeManager.prepare descartan in-place las entradas con ns");
    println!("   víctima ANTES de que la fase apply las publique al estado vivo. Sin");
    println!("   removeAll post-hoc, sin tocar el filesystem del mod, sin reiniciar.");
    println!("   El binomio (sweep + filter + dpReload) completa el modo desactivado");
    println!("   in-process para bloques/items/recetas/tags propios de la víctima.");
    println!("   Falta el envoltorio transaccional con preview de cascada (corte 5).");
}

fn probe_recipes_check(instancia: &str) {
    println!("[probe-recipes] F4 wcg.refs (de-risk): extracción estructural de aristas de receta");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    const VICTIM: &str = "mcwstairs";

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    // El servidor integrado y el RecipeManager vivo aparecen poco DESPUÉS de
    // game-ready (quickplay carga el mundo). Sondear: NOT_READY mientras el mundo
    // no esté cargado. CLAVE: ningún check() dentro del bucle — un fallo NO debe
    // abortar antes de cerrar el juego, o queda huérfano. Capturamos el resultado
    // y juzgamos DESPUÉS de kill_pid (mismo patrón que tx.probeSetBlock).
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(180);
    let mut id = 3u64;
    println!("[probe-recipes] disparando tx.probeRecipes para ns={VICTIM} (lee el RecipeManager VIVO, sin reload)");
    let outcome: Result<serde_json::Value, String> = loop {
        let r = request_p(&mut s, &mut reader, id, "tx.probeRecipes",
            serde_json::json!({ "ns": VICTIM }));
        id += 1;
        if r["ok"].is_object() {
            break Ok(r);
        }
        let code = r["err"]["code"].as_str().unwrap_or("?").to_string();
        let msg = r["err"]["msg"].as_str().unwrap_or("").to_string();
        println!("       tx.probeRecipes aún no listo: {code} ({msg})");
        if code != "NOT_READY" {
            break Err(format!("tx.probeRecipes falló: {code} — {msg}"));
        }
        if std::time::Instant::now() >= deadline {
            break Err("el servidor/mundo no estuvo listo dentro del timeout de 3 min".to_string());
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };

    // SIEMPRE cerrar el juego antes de juzgar (haya ido bien o mal).
    drop(reader);
    drop(s);
    std::thread::sleep(std::time::Duration::from_secs(2));
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    let r = match outcome {
        Ok(r) => r,
        Err(e) => {
            check(false, &e);
            unreachable!()
        }
    };
    let ok = &r["ok"];
    let rm_class = ok["recipeManagerClass"].as_str().unwrap_or("?");
    let rm_sig = ok["getRecipeManagerSig"].as_str().unwrap_or("?");
    let scanned = ok["scanned"].as_i64().unwrap_or(-1);
    let ext_res = ok["extractedResultOk"].as_i64().unwrap_or(-1);
    let ext_ing = ok["extractedIngredientsOk"].as_i64().unwrap_or(-1);
    let fully = ok["fullyExtracted"].as_i64().unwrap_or(-1);
    let walk_fail = ok["walkFailures"].as_i64().unwrap_or(-1);
    let distinct_types = ok["distinctRecipeTypes"].as_i64().unwrap_or(-1);
    let refs_to_victim = ok["refsToVictim"].as_i64().unwrap_or(-1);
    let victim_owned = ok["victimOwned"].as_i64().unwrap_or(-1);
    let duration = ok["durationMs"].as_i64().unwrap_or(-1);
    let coverage_pct = if scanned > 0 { fully * 100 / scanned } else { 0 };

    println!("       recipeManagerClass: {rm_class}");
    println!("       getRecipeManagerSig: {rm_sig}");
    println!("       scanned={scanned} fullyExtracted={fully} ({coverage_pct}%) \
              extractedResultOk={ext_res} extractedIngredientsOk={ext_ing} \
              walkFailures={walk_fail} distinctRecipeTypes={distinct_types} ({duration} ms)");

    if let Some(types) = ok["recipeTypeCounts"].as_object() {
        println!("       recipeTypeCounts:");
        for (k, v) in types { println!("         {k} = {v}"); }
    }

    let victim_refs_out: Vec<String> = ok["victimRefsOut"].as_array()
        .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
        .unwrap_or_default();
    println!("       victimOwned={victim_owned}; {VICTIM} refiere a namespaces: {victim_refs_out:?}");

    if let Some(sv) = ok["sampleVictimRecipes"].as_array() {
        println!("       muestra de recetas de {VICTIM} ({} mostradas):", sv.len().min(6));
        for e in sv.iter().take(6) {
            let rid = e["recipe"].as_str().unwrap_or("?");
            let res: Vec<&str> = e["result"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            let ing: Vec<&str> = e["ingredients"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("         {rid}\n           result={res:?}\n           ingredients={ing:?}");
        }
    }

    let edges_len = ok["sampleEdges"].as_array().map(|a| a.len()).unwrap_or(0);
    println!("       refsToVictim={refs_to_victim} (recetas de OTROS mods que usan items de {VICTIM}); sampleEdges={edges_len}");
    if let Some(se) = ok["sampleEdges"].as_array() {
        for e in se.iter().take(8) {
            let rid = e["recipe"].as_str().unwrap_or("?");
            let owner = e["owner"].as_str().unwrap_or("?");
            let refi: Vec<&str> = e["refItems"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("         [{owner}] {rid} → {refi:?}");
        }
    }
    if let Some(errs) = ok["errors"].as_array() {
        if !errs.is_empty() {
            println!("       errores del walker ({}):", errs.len());
            for er in errs.iter().take(10) { println!("         {}", er.as_str().unwrap_or("?")); }
        }
    }

    // ── Asserts de de-risk (lo que DEBE cumplirse si la extracción funciona).
    check(scanned > 0, &format!("el RecipeManager vivo tiene recetas (scanned={scanned})"));
    check(rm_sig != "unresolved" && rm_sig != "?", "getRecipeManager resuelto por forma");
    check(ext_res > 0, &format!("el walker extrae RESULTADOS estructuralmente ({ext_res}/{scanned})"));
    check(ext_ing > 0, &format!("el walker extrae INGREDIENTES estructuralmente ({ext_ing}/{scanned})"));
    check(victim_owned > 0,
        &format!("{VICTIM} tiene recetas propias ({victim_owned}) — base eyeball de extracción"));
    // Las escaleras de mcwstairs se fabrican con madera/tablones de minecraft (u otros
    // mods): si extrajimos sus ingredientes, victimRefsOut tiene ≥1 ns foráneo. Prueba
    // direccional fiable de extracción cross-ns sin depender de que otro mod use mcwstairs.
    check(!victim_refs_out.is_empty(),
        "las recetas de la víctima referencian items foráneos (extracción cross-ns probada)");
    // walkFailures es informativo, NO un assert duro: tipos que resisten el walker son
    // el dato de-risk a recolectar (special recipes sin datos extraíbles son normales).
    if walk_fail == 0 {
        println!("  ✔ walkFailures=0 — el walker no lanzó en ningún tipo del modpack");
    } else {
        println!("  ◎ walkFailures={walk_fail} — tipos que resisten el walker (ver errores arriba); \
                  dato de-risk a resolver en el índice completo, no un fallo de la sonda");
    }
    println!("  ◎ cobertura fullyExtracted={coverage_pct}% (recetas data-driven; las 'special' \
              sin datos extraíbles cuentan como parciales benignas — el humano juzga plausibilidad)");

    println!("\n   ═══ VEREDICTO F4 wcg.refs — SONDA DE RECETAS ═══");
    println!("   El primitivo de extracción de aristas de receta funciona estructuralmente");
    println!("   sobre el modpack real: el agente lee el RecipeManager VIVO (sin reload,");
    println!("   reusando el payload del corte 4) y un walker reflexivo genérico cosecha");
    println!("   los ids de item (ingredientes + resultado) de cada receta SIN código");
    println!("   por-tipo-de-receta. La cobertura numérica despeja el de-risk antes de");
    println!("   construir el índice completo wcg.refs (tags + loot + query invertida).");
}

fn probe_tags_check(instancia: &str) {
    println!("[probe-tags] F4 wcg.refs (de-risk): extracción de aristas de TAG (item+block) + tag-como-ingrediente");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    // Vanilla tiene tags ricos (minecraft:planks, minecraft:logs, …) en item Y block,
    // y muchas recetas usan tags vanilla como ingrediente → de-risk con señal abundante.
    const NS: &str = "minecraft";

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    // Mismo patrón que probe_recipes_check: sondear NOT_READY hasta que el servidor
    // integrado y los registros vivos existan; ningún check() dentro del bucle; juzgar
    // DESPUÉS de cerrar el juego para no dejar Minecraft huérfano.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(180);
    let mut id = 3u64;
    println!("[probe-tags] disparando tx.probeTags para ns={NS} (lee los tags VIVOS del registro, sin reload)");
    let outcome: Result<serde_json::Value, String> = loop {
        let r = request_p(&mut s, &mut reader, id, "tx.probeTags",
            serde_json::json!({ "ns": NS }));
        id += 1;
        if r["ok"].is_object() {
            break Ok(r);
        }
        let code = r["err"]["code"].as_str().unwrap_or("?").to_string();
        let msg = r["err"]["msg"].as_str().unwrap_or("").to_string();
        println!("       tx.probeTags aún no listo: {code} ({msg})");
        if code != "NOT_READY" {
            break Err(format!("tx.probeTags falló: {code} — {msg}"));
        }
        if std::time::Instant::now() >= deadline {
            break Err("el servidor/mundo no estuvo listo dentro del timeout de 3 min".to_string());
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };

    drop(reader);
    drop(s);
    std::thread::sleep(std::time::Duration::from_secs(2));
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    let r = match outcome {
        Ok(r) => r,
        Err(e) => {
            check(false, &e);
            unreachable!()
        }
    };
    let ok = &r["ok"];
    let tags_sig = ok["getTagsSig"].as_str().unwrap_or("?");
    let scanned_tags = ok["scannedTags"].as_i64().unwrap_or(-1);
    let tags_with_members = ok["tagsWithMembers"].as_i64().unwrap_or(-1);
    let tag_walk_fail = ok["tagWalkFailures"].as_i64().unwrap_or(-1);
    let distinct_regs = ok["distinctTagRegistries"].as_i64().unwrap_or(-1);
    let victim_tags = ok["victimTags"].as_i64().unwrap_or(-1);
    let tags_containing_victim = ok["tagsContainingVictim"].as_i64().unwrap_or(-1);
    let recipes_using_tags = ok["recipesUsingTags"].as_i64().unwrap_or(-1);
    let recipes_using_victim_tags = ok["recipesUsingVictimTags"].as_i64().unwrap_or(-1);
    let duration = ok["durationMs"].as_i64().unwrap_or(-1);

    println!("       getTagsSig: {tags_sig}");
    println!("       scannedTags={scanned_tags} tagsWithMembers={tags_with_members} \
              tagWalkFailures={tag_walk_fail} distinctTagRegistries={distinct_regs} ({duration} ms)");
    println!("       victimTags={victim_tags} (tags del ns {NS}); \
              tagsContainingVictim={tags_containing_victim} (tags ajenos con miembros de {NS})");

    // Mitad 1 eyeball: tag → miembros reales.
    if let Some(st) = ok["sampleTags"].as_array() {
        println!("       muestra de tags vivos ({} mostrados):", st.len().min(10));
        for e in st.iter().take(10) {
            let tag = e["tag"].as_str().unwrap_or("?");
            let reg = e["registry"].as_str().unwrap_or("?");
            let mc = e["memberCount"].as_i64().unwrap_or(-1);
            let mem: Vec<&str> = e["members"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("         [{reg}] {tag} ({mc} miembros) → {mem:?}");
        }
    }

    // Mitad 2 eyeball: receta → tag(s) usados como ingrediente.
    println!("       recipesUsingTags={recipes_using_tags} (recetas con ≥1 Ingredient respaldado por tag); \
              recipesUsingVictimTags={recipes_using_victim_tags}");
    if let Some(se) = ok["sampleRecipeTagEdges"].as_array() {
        println!("       muestra de aristas receta→tag ({} mostradas):", se.len().min(8));
        for e in se.iter().take(8) {
            let rid = e["recipe"].as_str().unwrap_or("?");
            let tags: Vec<&str> = e["tags"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            let refi: Vec<&str> = e["victimRefItems"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("         {rid} → tags={tags:?} (refItems de {NS}: {refi:?})");
        }
    }
    if let Some(errs) = ok["errors"].as_array() {
        if !errs.is_empty() {
            println!("       errores/diagnósticos ({}):", errs.len());
            for er in errs.iter().take(10) { println!("         {}", er.as_str().unwrap_or("?")); }
        }
    }

    // ── Asserts de de-risk.
    check(tags_sig != "unresolved" && tags_sig != "?", "getTags resuelto por comportamiento");
    check(scanned_tags > 0, &format!("el registro vivo expone tags (scannedTags={scanned_tags})"));
    check(tags_with_members > 0,
        &format!("la mitad fácil funciona: los tags resuelven a sus miembros ({tags_with_members})"));
    check(distinct_regs >= 2,
        &format!("item Y block tags ambos cosechados (distinctTagRegistries={distinct_regs})"));
    check(recipes_using_tags > 0,
        &format!("la mitad dura funciona: tag-como-ingrediente es alcanzable y atribuible ({recipes_using_tags} recetas)"));
    // tagWalkFailures es informativo, NO assert duro (como walkFailures de recetas).
    if tag_walk_fail == 0 {
        println!("  ✔ tagWalkFailures=0 — todos los tags se leyeron sin lanzar");
    } else {
        println!("  ◎ tagWalkFailures={tag_walk_fail} — tags que resisten el lector (ver errores arriba); \
                  dato de-risk a recolectar, no un fallo de la sonda");
    }
    println!("  ◎ tagsContainingVictim={tags_containing_victim} / recipesUsingVictimTags={recipes_using_victim_tags} \
              (cross-ns informativo: ns={NS} es la víctima, casi todos los tags/recetas lo referencian)");

    println!("\n   ═══ VEREDICTO F4 wcg.refs — SONDA DE TAGS ═══");
    println!("   El primitivo de extracción de aristas de tag (item+block) funciona sobre el");
    println!("   modpack real: el agente lee los tags VIVOS del registro (sin reload), resuelve");
    println!("   cada tag a sus miembros, y atribuye los tags usados como ingrediente a sus");
    println!("   recetas SIN perder la identidad del tag. El índice completo de aristas de tag");
    println!("   puede plegarse en wcg.refs sin riesgo (siguiente: pliegue + loot + SQLite).");
}

fn probe_loot_check(instancia: &str) {
    println!("[probe-loot] F4 wcg.refs (de-risk): localizar el Registry<LootTable> VIVO + cosechar drops");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    // Vanilla tiene cientos de loot tables (cofres, mobs, bloques) que sueltan items vanilla
    // → de-risk con señal abundante. La incógnita: loot NO está en BuiltInRegistries.
    const NS: &str = "minecraft";

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    // Mismo patrón que probe_tags_check: sondear NOT_READY hasta que el servidor integrado
    // y sus registros reloadable existan; ningún check() dentro del bucle; juzgar DESPUÉS
    // de cerrar el juego para no dejar Minecraft huérfano.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(180);
    let mut id = 3u64;
    println!("[probe-loot] disparando tx.probeLoot para ns={NS} (busca el Registry<LootTable> vivo por comportamiento)");
    let outcome: Result<serde_json::Value, String> = loop {
        let r = request_p(&mut s, &mut reader, id, "tx.probeLoot",
            serde_json::json!({ "ns": NS }));
        id += 1;
        if r["ok"].is_object() {
            break Ok(r);
        }
        let code = r["err"]["code"].as_str().unwrap_or("?").to_string();
        let msg = r["err"]["msg"].as_str().unwrap_or("").to_string();
        println!("       tx.probeLoot aún no listo: {code} ({msg})");
        if code != "NOT_READY" {
            break Err(format!("tx.probeLoot falló: {code} — {msg}"));
        }
        if std::time::Instant::now() >= deadline {
            break Err("el servidor/mundo no estuvo listo dentro del timeout de 3 min".to_string());
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };

    drop(reader);
    drop(s);
    std::thread::sleep(std::time::Duration::from_secs(2));
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    let r = match outcome {
        Ok(r) => r,
        Err(e) => {
            check(false, &e);
            unreachable!()
        }
    };
    let ok = &r["ok"];
    let loot_sig = ok["lootRegistrySig"].as_str().unwrap_or("?");
    let loot_class = ok["lootRegistryClass"].as_str().unwrap_or("?");
    let getid_resolved = ok["lootGetIdResolved"].as_bool().unwrap_or(false);
    let scanned = ok["scanned"].as_i64().unwrap_or(-1);
    let ids_resolved = ok["idsResolved"].as_i64().unwrap_or(-1);
    let tables_with_items = ok["tablesWithItems"].as_i64().unwrap_or(-1);
    let walk_fail = ok["walkFailures"].as_i64().unwrap_or(-1);
    let total_item_refs = ok["totalItemRefs"].as_i64().unwrap_or(-1);
    let distinct_items = ok["distinctItems"].as_i64().unwrap_or(-1);
    let refs_to_victim = ok["refsToVictim"].as_i64().unwrap_or(-1);
    let victim_owned = ok["victimOwned"].as_i64().unwrap_or(-1);
    let duration = ok["durationMs"].as_i64().unwrap_or(-1);

    println!("       lootRegistrySig: {loot_sig}");
    println!("       lootRegistryClass: {loot_class}  lootGetIdResolved={getid_resolved}");
    println!("       scanned={scanned} idsResolved={ids_resolved} tablesWithItems={tables_with_items} \
              walkFailures={walk_fail} ({duration} ms)");
    println!("       totalItemRefs={total_item_refs} distinctItems={distinct_items}");
    println!("       victimOwned={victim_owned} (loot tables del ns {NS}); \
              refsToVictim={refs_to_victim} (tables ajenas que sueltan un item de {NS})");

    // Eyeball: loot table → items que suelta.
    if let Some(st) = ok["sampleTables"].as_array() {
        println!("       muestra de loot tables vivas ({} mostradas):", st.len().min(15));
        for e in st.iter().take(15) {
            let table = e["table"].as_str().unwrap_or("?");
            let drops: Vec<&str> = e["drops"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("         {table} → {drops:?}");
        }
    }
    if let Some(errs) = ok["errors"].as_array() {
        if !errs.is_empty() {
            println!("       errores/diagnósticos ({}):", errs.len());
            for er in errs.iter().take(10) { println!("         {}", er.as_str().unwrap_or("?")); }
        }
    }

    // ── Asserts de de-risk.
    check(loot_sig != "unresolved" && !loot_sig.starts_with("unresolved") && loot_sig != "?",
        &format!("el Registry<LootTable> vivo se localizó por comportamiento ({loot_sig})"));
    check(scanned > 0, &format!("el registro reloadable expone loot tables (scanned={scanned})"));
    check(tables_with_items > 0,
        &format!("el walker cosecha drops de las loot tables (tablesWithItems={tables_with_items})"));
    check(distinct_items > 0,
        &format!("se extraen items distintos del grafo de loot (distinctItems={distinct_items})"));
    check(getid_resolved && ids_resolved > 0,
        &format!("la identidad de cada loot table es resoluble (idsResolved={ids_resolved})"));
    // walkFailures informativo, NO assert duro (como en recetas/tags).
    if walk_fail == 0 {
        println!("  ✔ walkFailures=0 — todas las loot tables se leyeron sin lanzar");
    } else {
        println!("  ◎ walkFailures={walk_fail} — loot tables que resisten el walker (ver errores); dato de-risk, no fallo");
    }
    println!("  ◎ victimOwned={victim_owned} / refsToVictim={refs_to_victim} \
              (cross-ns informativo: ns={NS} dueño de casi todas las loot tables vanilla)");

    println!("\n   ═══ VEREDICTO F4 wcg.refs — SONDA DE LOOT ═══");
    println!("   La incógnita estructural queda despejada: las loot tables NO están en");
    println!("   BuiltInRegistries, pero el agente LOCALIZA el Registry<LootTable> vivo por");
    println!("   COMPORTAMIENTO (un class_2378 cuyos elementos son class_52), sin reload y sin");
    println!("   nombres de método, y cosecha sus drops con el mismo walker (harvestItems) ya");
    println!("   validado en recetas/tags. Las aristas loot→item pueden plegarse en wcg.refs");
    println!("   sin sorpresa estructural (siguiente: pliegue de loot + persistencia SQLite).");
}

/// F4 wcg.refs — query invertida de cascada (recetas). Sobre el modpack real:
/// "si desactivas el ns V, ¿qué recetas de OTROS mods se rompen porque usan sus
/// items?", agrupado por dueño. Dos disparos: V=minecraft (debe romper recetas de
/// varios mods, mcwstairs incluido, porque las escaleras usan madera vanilla) y
/// V=mcwstairs (mod hoja: 0 recetas ajenas rotas — respuesta honesta). El índice se
/// construye una vez y se reusa entre queries.
fn wcg_refs_check(instancia: &str) {
    println!("[wcg-refs] F4 query invertida de cascada (recetas): '¿qué rompo si desactivo V?'");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    const LEAF: &str = "mcwstairs"; // mod hoja: nadie usa sus items → caso cero

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    // El RecipeManager vivo aparece poco DESPUÉS de game-ready. Sondear con la 1ª
    // query (ns=minecraft) hasta que el mundo cargue. Ningún check() dentro del
    // bucle: juzgamos DESPUÉS de cerrar el juego (patrón tx.probeSetBlock).
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(180);
    let mut id = 3u64;
    println!("[wcg-refs] disparando wcg.refs{{ns:minecraft}} (construye el índice invertido + invierte)");
    let q1: Result<serde_json::Value, String> = loop {
        let r = request_p(&mut s, &mut reader, id, "wcg.refs",
            serde_json::json!({ "ns": "minecraft" }));
        id += 1;
        if r["ok"].is_object() {
            break Ok(r);
        }
        let code = r["err"]["code"].as_str().unwrap_or("?").to_string();
        let msg = r["err"]["msg"].as_str().unwrap_or("").to_string();
        println!("       wcg.refs aún no listo: {code} ({msg})");
        if code != "NOT_READY" {
            break Err(format!("wcg.refs falló: {code} — {msg}"));
        }
        if std::time::Instant::now() >= deadline {
            break Err("el servidor/mundo no estuvo listo dentro del timeout de 3 min".to_string());
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };

    // 2ª query (caso cero) sólo si la 1ª arrancó: el servidor ya está vivo, debe
    // reusar el índice cacheado. Captura sin juzgar.
    let q2: Result<serde_json::Value, String> = match &q1 {
        Ok(_) => {
            println!("[wcg-refs] disparando wcg.refs{{ns:{LEAF}}} (caso cero — debe reusar el índice)");
            let r = request_p(&mut s, &mut reader, id, "wcg.refs",
                serde_json::json!({ "ns": LEAF }));
            if r["ok"].is_object() {
                Ok(r)
            } else {
                let code = r["err"]["code"].as_str().unwrap_or("?");
                let msg = r["err"]["msg"].as_str().unwrap_or("");
                Err(format!("wcg.refs{{ns:{LEAF}}} falló: {code} — {msg}"))
            }
        }
        Err(_) => Err("omitida (la 1ª query no arrancó)".to_string()),
    };

    // SIEMPRE cerrar el juego antes de juzgar.
    drop(reader);
    drop(s);
    std::thread::sleep(std::time::Duration::from_secs(2));
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    let r1 = match q1 {
        Ok(r) => r,
        Err(e) => { check(false, &e); unreachable!() }
    };

    // ── Query 1: desactivar minecraft ──────────────────────────────────────────
    let ok1 = &r1["ok"];
    let broken1 = ok1["brokenRecipes"].as_i64().unwrap_or(-1);
    let weakened1 = ok1["weakenedRecipes"].as_i64().unwrap_or(-1);
    let affected_result1 = ok1["affectedResultRecipes"].as_i64().unwrap_or(-1);
    let owners1 = ok1["affectedOwners"].as_i64().unwrap_or(-1);
    let edges_total1 = ok1["edgesTotal"].as_i64().unwrap_or(-1);
    let idx_scanned = ok1["index"]["scanned"].as_i64().unwrap_or(-1);
    let idx_built_ms = ok1["index"]["builtMs"].as_i64().unwrap_or(-1);
    let idx_keys = ok1["index"]["keys"].as_i64().unwrap_or(-1);
    let idx_reused1 = ok1["index"]["reused"].as_bool().unwrap_or(false);
    let dur1 = ok1["durationMs"].as_i64().unwrap_or(-1);

    println!("\n   ── wcg.refs{{ns:minecraft}} (desactivar minecraft) ──");
    println!("       brokenRecipes={broken1} (recetas ajenas que dejan de craftearse)");
    println!("       weakenedRecipes={weakened1} (usan un tag con items de minecraft que sobrevive)");
    println!("       affectedResultRecipes={affected_result1} (raras: producen un item de minecraft)");
    println!("       affectedOwners={owners1}  edgesTotal={edges_total1}  ({dur1} ms)");
    println!("       index: scanned={idx_scanned} keys={idx_keys} builtMs={idx_built_ms} reused={idx_reused1}");

    let mut leaf_in_owners = false;
    let mut leaf_broken = 0i64;
    if let Some(by_owner) = ok1["byOwner"].as_object() {
        println!("       byOwner (mods afectados al quitar minecraft, {} dueños):", by_owner.len());
        // Imprime hasta 15 dueños ordenados por 'broken' desc para legibilidad.
        let mut rows: Vec<(&String, i64, i64, i64)> = by_owner.iter()
            .map(|(k, v)| (k, v["broken"].as_i64().unwrap_or(0),
                           v["affectedResult"].as_i64().unwrap_or(0),
                           v["weakened"].as_i64().unwrap_or(0)))
            .collect();
        rows.sort_by(|a, b| b.1.cmp(&a.1));
        for (owner, broken, ares, weak) in rows.iter().take(15) {
            println!("         {owner}: broken={broken} weakened={weak} affectedResult={ares}");
        }
        if rows.len() > 15 { println!("         … (+{} dueños más)", rows.len() - 15); }
        if let Some(v) = by_owner.get(LEAF) {
            leaf_in_owners = true;
            leaf_broken = v["broken"].as_i64().unwrap_or(0);
            let items: Vec<&str> = v["items"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("       → {LEAF}: broken={leaf_broken}; items de minecraft que usa: {items:?}");
        }
    }

    // Aristas de item (ingredient/result) y vía-tag por separado para legibilidad.
    let mut via_tag_count = 0i64;
    let mut any_tag_breaks = false;
    if let Some(edges) = ok1["edges"].as_array() {
        println!("       muestra de aristas de item ({} de {edges_total1}):", edges.len().min(8));
        for e in edges.iter().filter(|e| e["role"].as_str() != Some("ingredient-via-tag")).take(8) {
            let rid = e["recipe"].as_str().unwrap_or("?");
            let owner = e["owner"].as_str().unwrap_or("?");
            let role = e["role"].as_str().unwrap_or("?");
            let refi: Vec<&str> = e["refItems"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("         [{owner}] {rid} ({role}) ← {refi:?}");
        }
        let via_tag: Vec<&serde_json::Value> = edges.iter()
            .filter(|e| e["role"].as_str() == Some("ingredient-via-tag")).collect();
        via_tag_count = via_tag.len() as i64;
        any_tag_breaks = via_tag.iter().any(|e| e["breaks"].as_bool().unwrap_or(false));
        println!("       muestra de aristas vía-tag ({} aristas; breaks=true ⇒ el tag se vacía):", via_tag_count);
        for e in via_tag.iter().take(8) {
            let rid = e["recipe"].as_str().unwrap_or("?");
            let owner = e["owner"].as_str().unwrap_or("?");
            let breaks = e["breaks"].as_bool().unwrap_or(false);
            let tags: Vec<&str> = e["tags"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            let refi: Vec<&str> = e["refItems"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("         [{owner}] {rid} (breaks={breaks}) ← {tags:?} ⊇ {refi:?}");
        }
    }

    // Tag-como-definición (affectedTags): qué tags pierden miembros al quitar minecraft.
    let affected_tags_total1 = ok1["affectedTagsTotal"].as_i64().unwrap_or(-1);
    let emptied_tags1 = ok1["emptiedTags"].as_i64().unwrap_or(-1);
    println!("       affectedTags={affected_tags_total1} (tags de item que pierden miembros), emptiedTags={emptied_tags1} (se vacían del todo)");
    if let Some(at) = ok1["affectedTags"].as_array() {
        for t in at.iter().take(8) {
            let tag = t["tag"].as_str().unwrap_or("?");
            let total = t["membersTotal"].as_i64().unwrap_or(-1);
            let victim = t["victimMembers"].as_i64().unwrap_or(-1);
            let emptied = t["emptied"].as_bool().unwrap_or(false);
            println!("         {tag}: {victim}/{total} miembros son de minecraft (emptied={emptied})");
        }
    }

    // Aristas de loot (loot-drop): tablas ajenas que sueltan items de minecraft (pierden el drop).
    let affected_loot1 = ok1["affectedLootTables"].as_i64().unwrap_or(-1);
    let loot_scanned1 = ok1["index"]["lootScanned"].as_i64().unwrap_or(-1);
    println!("       affectedLootTables={affected_loot1} (tablas ajenas que sueltan items de minecraft; lootScanned={loot_scanned1})");
    if let Some(edges) = ok1["edges"].as_array() {
        let mut shown = 0;
        for e in edges.iter() {
            if e["role"].as_str() != Some("loot-drop") { continue; }
            let table = e["table"].as_str().unwrap_or("?");
            let owner = e["owner"].as_str().unwrap_or("?");
            let refi: Vec<&str> = e["refItems"].as_array()
                .map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            println!("         [{owner}] {table} → suelta {refi:?}");
            shown += 1;
            if shown >= 8 { break; }
        }
    }

    // ── Query 2: desactivar mcwstairs (caso cero) ───────────────────────────────
    let r2 = match q2 {
        Ok(r) => r,
        Err(e) => { check(false, &e); unreachable!() }
    };
    let ok2 = &r2["ok"];
    let broken2 = ok2["brokenRecipes"].as_i64().unwrap_or(-1);
    let weakened2 = ok2["weakenedRecipes"].as_i64().unwrap_or(-1);
    let owners2 = ok2["affectedOwners"].as_i64().unwrap_or(-1);
    let affected_tags_total2 = ok2["affectedTagsTotal"].as_i64().unwrap_or(-1);
    let emptied_tags2 = ok2["emptiedTags"].as_i64().unwrap_or(-1);
    let affected_loot2 = ok2["affectedLootTables"].as_i64().unwrap_or(-1);
    let idx_reused2 = ok2["index"]["reused"].as_bool().unwrap_or(false);
    println!("\n   ── wcg.refs{{ns:{LEAF}}} (desactivar {LEAF}, caso cero) ──");
    println!("       brokenRecipes={broken2}  weakenedRecipes={weakened2}  affectedOwners={owners2}  index.reused={idx_reused2}");
    println!("       affectedTags={affected_tags_total2} emptiedTags={emptied_tags2} (los items de {LEAF} pueden vivir en tags c:/vanilla compartidos)");
    println!("       affectedLootTables={affected_loot2} (tablas ajenas que sueltan items de {LEAF}, si las hay)");

    // ── Asserts duros ───────────────────────────────────────────────────────────
    check(idx_scanned > 0, &format!("el índice invertido se construyó (scanned={idx_scanned}, keys={idx_keys})"));
    check(broken1 > 0,
        &format!("desactivar minecraft ROMPE recetas ajenas (brokenRecipes={broken1})"));
    check(owners1 > 0,
        &format!("la cascada afecta a varios mods (affectedOwners={owners1})"));
    check(leaf_in_owners && leaf_broken > 0,
        &format!("{LEAF} está entre los afectados con broken>0 (sus escaleras usan madera vanilla) — broken={leaf_broken}"));
    // La mitad dura plegada: las aristas vía-tag se detectan Y la decisión de dos niveles
    // discrimina (aquí decide ROTA porque los tags usados son vanilla-puros → se vacían).
    check(via_tag_count > 0,
        &format!("aristas vía-tag detectadas y plegadas en el índice (via_tag={via_tag_count})"));
    check(any_tag_breaks,
        "al menos una receta ajena queda ROTA vía tag (un tag vanilla-puro se vacía al quitar minecraft)");
    check(broken2 == 0,
        &format!("desactivar {LEAF} NO rompe HARD recetas ajenas (mod hoja; brokenRecipes={broken2})"));
    // Tag-como-definición: desactivar minecraft VACÍA tags (sus items pueblan #planks, #logs, …).
    check(affected_tags_total1 > 0,
        &format!("desactivar minecraft afecta tags de item (affectedTags={affected_tags_total1})"));
    check(emptied_tags1 > 0,
        &format!("al menos un tag se VACÍA por completo al quitar minecraft (emptiedTags={emptied_tags1})"));
    // Loot plegado: tablas ajenas que sueltan items de minecraft (mismas que tx.probeLoot vio,
    // refsToVictim>0). Quitar minecraft no rompe esas tablas, solo las afecta (pierden el drop).
    check(loot_scanned1 > 0,
        &format!("el índice escaneó la registry de loot al construirse (lootScanned={loot_scanned1})"));
    check(affected_loot1 > 0,
        &format!("desactivar minecraft AFECTA tablas de loot ajenas (sueltan items vanilla; affectedLootTables={affected_loot1})"));

    // Informativos.
    if idx_reused2 {
        println!("  ✔ index.reused=true en la 2ª query — el índice se construyó una vez y se reusó");
    } else {
        println!("  ◎ index.reused=false en la 2ª query — se reconstruyó (epoch cambió o caché perdida)");
    }
    // weakenedRecipes=0 en ESTE modpack es honesto, no un fallo: la debilitación requiere un tag
    // compartido que SOBREVIVA (con miembros no-V). Al quitar minecraft (la base) los tags usados
    // (#wooden_slabs, #logs, c:dyes/white) son vanilla-puros → se VACÍAN → rotura, nunca debilitación.
    // Ningún mod del pack co-habita esos tags con items propios. La rama 'weakened' está implementada
    // y es correcta por construcción; este pack simplemente no la dispara.
    println!("  ◎ weakenedRecipes(minecraft)={weakened1} — 0 honesto: los tags usados son vanilla-puros, se vacían (rotura), no sobreviven");
    println!("  ◎ {LEAF}: weakenedRecipes={weakened2} affectedOwners={owners2} (caso cero: sus items no viven en tags ajenos)");
    println!("  ◎ affectedResultRecipes={affected_result1} (recetas que producen un item de minecraft)");
    println!("  ◎ edgesTotal={edges_total1} (aristas entrantes totales; edges[] capada para compacidad)");
    println!("  ◎ affectedTags(minecraft)={affected_tags_total1} emptiedTags={emptied_tags1} (tag-como-definición: tags que pierden/vacían miembros)");
    println!("  ◎ {LEAF}: affectedTags={affected_tags_total2} emptiedTags={emptied_tags2} (informativo: items hoja en tags c:/vanilla)");
    println!("  ◎ affectedLootTables(minecraft)={affected_loot1} lootScanned={loot_scanned1} (tablas ajenas que pierden un drop vanilla; afectadas, no rotas)");
    println!("  ◎ {LEAF}: affectedLootTables={affected_loot2} (informativo: tablas ajenas que sueltan items de {LEAF})");

    println!("\n   ═══ VEREDICTO F4 wcg.refs — CASCADA RECETAS + TAGS (2 NIVELES) + LOOT ═══");
    println!("   La query invertida responde '¿qué rompo si desactivo V?' agrupado por mod dueño,");
    println!("   sobre el modpack real. Aristas de tag plegadas en el índice congelado con semántica");
    println!("   de dos niveles: ROTA si el tag se vacía de miembros no-V, DEBILITADA si sobrevive.");
    println!("   Desactivar minecraft rompe recetas por item directo Y vía tag (#wooden_slabs, #logs,");
    println!("   c:dyes/white se vacían → aristas vía-tag breaks=true, atribución correcta).");
    println!("   La debilitación no se dispara en este pack (ningún mod co-habita esos tags), 0 honesto;");
    println!("   mcwstairs no rompe HARD nada (mod hoja). Índice construido una vez y reusado.");
    println!("   Tag-como-definición plegado (affectedTags): tags que pierden/vacían miembros al quitar V.");
    println!("   Loot plegado (loot-drop): tablas ajenas que sueltan items de V quedan AFECTADAS (pierden");
    println!("   el drop), nunca rotura dura — paralelo a affectedResult. Mismo índice, una sola pasada.");
    println!("   Siguiente: persistencia SQLite del índice invertido (tabla refs).");
}

fn tier1_3_check(instancia: &str) {
    println!("[tier1-3] Tier 1 corte 3 (in-process): filtro de registro vivo (veto de setBlockState)");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);

    const VICTIM: &str = "mcwstairs";

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    let mut id = 3u64;
    println!("       esperando a que el mundo termine de cargar (setBlocks estable 6s)...");
    let stable = wait_setblocks_quiescent(&mut s, &mut reader, &mut id, 6, 120);
    println!("       mundo cargado (setBlocks={stable} estable). Ahora puedes interactuar.\n");

    // ── Fase A: baseline. El usuario coloca SIN veto, para confirmar que en este
    //    mundo / con este modset las escaleras se colocan normalmente. Sin baseline,
    //    el test podría aprobar trivialmente si el usuario no consigue interactuar
    //    por otra razón (cliente roto, distancia del spawn, etc.).
    println!("   >>> FASE A (baseline, SIN veto): coloca 3-4 escaleras de {VICTIM} cerca del spawn.");
    println!("   >>> Se detectará automáticamente cuando wcg.counts.{VICTIM}.blocks > 0.\n");
    let pre_a = wait_counts_positive(&mut s, &mut reader, &mut id, VICTIM, 300);
    check(pre_a > 0, &format!("FASE A: huella detectada sin veto (blocks={pre_a})"));

    // ── Snapshot del veto en reposo: ningún hit, ningún passthrough.
    let stats_a = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    let veto_armed_a = stats_a["ok"]["vetoArmed"].as_i64().unwrap_or(-1);
    let veto_hits_a = stats_a["ok"]["vetoHits"].as_i64().unwrap_or(-1);
    println!("       PRE-arm: vetoArmed={veto_armed_a} vetoHits={veto_hits_a}");
    check(veto_armed_a == 0, "PRE-arm: el veto no está armado (vetoArmed=0)");
    check(veto_hits_a == 0, "PRE-arm: ningún veto ejercido todavía (vetoHits=0)");

    // ── Fase B: ARMAR veto.
    println!("[tier1-3] armando veto para [{VICTIM}]");
    let arm = request_p(&mut s, &mut reader, id, "ledger.vetoArm",
        serde_json::json!({ "namespaces": [VICTIM] }));
    id += 1;
    check(
        arm["ok"]["armed"].as_bool().unwrap_or(false),
        "ledger.vetoArm armado",
    );
    check(
        arm["ok"]["count"].as_i64().unwrap_or(0) == 1,
        "vetoArm reporta count=1",
    );

    // Snapshot del agregado al armar — el delta del usuario en B se mide sobre éste.
    let pre_b = request_p(&mut s, &mut reader, id, "wcg.counts",
        serde_json::json!({ "ns": VICTIM }))["ok"]["blocks"].as_i64().unwrap_or(0);
    id += 1;
    println!("       wcg.counts.blocks al armar = {pre_b}");
    let hits_pre_b = request(&mut s, &mut reader, id, "ledger.stats")["ok"]["vetoHits"]
        .as_i64().unwrap_or(0);
    id += 1;

    println!("   >>> FASE B (CON veto): intenta colocar 5-6 escaleras de {VICTIM}. NO deben aparecer.");
    println!("   >>> Quédate quieto cuando termines. Esperando ≥5 vetoHits…\n");

    // Detector dirigido: vetoHits Δ≥5 (cada intento de colocación debe disparar el
    // hook). El tiempo de espera es generoso (5 min) — el usuario está colocando.
    let veto_target = 5i64;
    let veto_after = wait_veto_hits_delta(&mut s, &mut reader, &mut id, hits_pre_b, veto_target, 300);
    check(
        veto_after >= veto_target,
        &format!("FASE B: vetoHits Δ={veto_after} ≥ {veto_target} (cada intento se vetó)"),
    );

    // Margen para que el usuario suelte definitivamente el ratón.
    std::thread::sleep(std::time::Duration::from_secs(5));

    let post_b = request_p(&mut s, &mut reader, id, "wcg.counts",
        serde_json::json!({ "ns": VICTIM }))["ok"]["blocks"].as_i64().unwrap_or(-1);
    id += 1;
    println!(
        "       wcg.counts.blocks tras FASE B = {post_b} (pre-arm fue {pre_b}, Δ={})",
        post_b - pre_b
    );
    check(
        post_b == pre_b,
        &format!("FASE B: la presencia del namespace NO crece bajo veto ({post_b} == {pre_b})"),
    );

    let stats_b = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    let by_inc_b = stats_b["ok"]["byChunkInc"].as_i64().unwrap_or(-1);
    let by_inc_a_pre = stats_a["ok"]["byChunkInc"].as_i64().unwrap_or(0);
    println!(
        "       byChunkInc: A_pre={by_inc_a_pre} B={by_inc_b} (Δ={}). \
         Idealmente Δ=0: si vetamos al entry, onSetBlock al ARETURN no corre.",
        by_inc_b - by_inc_a_pre
    );
    // No es asserción dura — un onSetBlock previo legítimo (gravity, tick) puede
    // incrementar byChunk durante B; lo que el corte garantiza es que las colocaciones
    // del usuario no llegan al chunk, que es lo que mide post_b == pre_b arriba.

    // ── Fase C: DESARMAR. El usuario debe volver a colocar normalmente.
    println!("[tier1-3] desarmando veto");
    let dis = request(&mut s, &mut reader, id, "ledger.vetoDisarm"); id += 1;
    check(
        dis["ok"]["disarmed"].as_bool().unwrap_or(false),
        "ledger.vetoDisarm desarmado",
    );
    let stats_dis = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    check(
        stats_dis["ok"]["vetoArmed"].as_i64().unwrap_or(-1) == 0,
        "POST-disarm: vetoArmed=0",
    );

    println!("   >>> FASE C (re-baseline, SIN veto): coloca 2-3 escaleras más de {VICTIM}.");
    println!("   >>> Tras desarmar, deben aparecer normalmente — idempotencia del veto.\n");
    let post_c = wait_counts_delta(&mut s, &mut reader, &mut id, VICTIM, post_b, 1, 300);
    check(
        post_c > post_b,
        &format!("FASE C: tras desarmar, wcg.counts.blocks crece de nuevo ({post_b} → {post_c})"),
    );

    // ── Cierre.
    let stats_z = request(&mut s, &mut reader, id, "ledger.stats"); id += 1;
    println!(
        "       FINAL: vetoHits={} vetoPassthrough={} (passthrough = setBlock visto con veto armado y NO víctima)",
        stats_z["ok"]["vetoHits"], stats_z["ok"]["vetoPassthrough"]
    );

    println!("  … fase 3 completada; cierro juego");
    drop(reader);
    drop(s);
    std::thread::sleep(std::time::Duration::from_secs(2));
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    println!("\n   ═══ VEREDICTO TIER 1 CORTE 3 ═══");
    println!("   Filtro de registro vivo: el agente VETA setBlockState al entry");
    println!("   devolviendo null (señal canónica de MC \"no hubo cambio\"). El packet");
    println!("   no sale al cliente, el item no se consume, neighbor updates no");
    println!("   disparan. Idempotente: desarmar restituye la colocación normal.");
    println!("   El binomio (sweep del corte 2 + filter del corte 3) es la mitad");
    println!("   estable de Tier 1 in-process; falta el reload de datapacks (corte 4)");
    println!("   y la transacción envolvente (corte 5).");
}

// Espera a que vetoHits crezca delta unidades sobre base. Usado por --tier1-3 fase B.
fn wait_veto_hits_delta(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: &mut u64,
    base: i64,
    delta: i64,
    timeout_secs: u64,
) -> i64 {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
    let mut ticks = 0u32;
    loop {
        let v = request(s, reader, *id, "ledger.stats");
        *id += 1;
        let cur = v["ok"]["vetoHits"].as_i64().unwrap_or(0);
        let d = cur - base;
        if d >= delta {
            return d;
        }
        if std::time::Instant::now() >= deadline {
            return d;
        }
        ticks += 1;
        if ticks % 5 == 0 {
            println!("       … sigo esperando intentos de colocación del usuario (vetoHits Δ={d}/{delta})");
        }
        std::thread::sleep(std::time::Duration::from_secs(3));
    }
}

// Espera a que wcg.counts.blocks crezca delta unidades sobre base. Fase C.
fn wait_counts_delta(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: &mut u64,
    ns: &str,
    base: i64,
    delta: i64,
    timeout_secs: u64,
) -> i64 {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
    let mut ticks = 0u32;
    loop {
        let v = request_p(s, reader, *id, "wcg.counts", serde_json::json!({ "ns": ns }));
        *id += 1;
        let cur = v["ok"]["blocks"].as_i64().unwrap_or(0);
        if cur - base >= delta {
            return cur;
        }
        if std::time::Instant::now() >= deadline {
            return cur;
        }
        ticks += 1;
        if ticks % 5 == 0 {
            println!("       … sigo esperando colocaciones post-desarme (blocks={cur}, base={base})");
        }
        std::thread::sleep(std::time::Duration::from_secs(3));
    }
}

// ─────────────────────── Tier 1 corte 2: captura quirúrgica zero-IO ────────────────────
//
// Misma coreografía que el corte 1 con dos diferencias clave en las aserciones:
//   · aggregateStale=false  (pieza 2: byChunk se mantiene vivo desde onSetBlock)
//   · wcg.counts{ns}=0 post-sweep — el agregado refleja la realidad sin esperar save
//   · dirtied=0 (la pieza 1 ya no fuerza dirty con setBlock dummies)
//   · copyOfCalls>0 / writesTriggered>0 (señal del descubrimiento estructural de
//     SerializableChunkData.copyOf/write y ChunkSource.getChunkNow)
// La aserción "zero-IO" en sí es de diseño (copyOf().write() no toca disco por
// construcción); el harness valida la ausencia de la deuda del corte 1 (dirtied=0).
fn tier1_2_check(instancia: &str) {
    println!("[tier1-2] Tier 1 corte 2 (in-process): captura quirúrgica zero-IO + byChunk in-vivo");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let world = saves.join("mksa-test");

    const VICTIM: &str = "mcwstairs";

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();
    let (pid, mut s, mut reader) = open_session(&handle, &games, instancia);

    let mut id = 3u64;
    let (_w0, _rp0, _rt0) = ledger_stats(&mut s, &mut reader, id);
    id += 1;

    // ── ESPERAR a que el mundo termine de cargar antes de pedir input al usuario.
    //    setBlocks cuenta TODA mutación (worldgen, light, gravity, ticks). Durante
    //    la carga del mundo hay decenas de mutaciones espurias; tomar baseline ahora
    //    haría que cualquier Δ≥5 dispare prematuramente sin que el usuario coloque
    //    nada. Esperamos a que setBlocks se quede estable 6s seguidos = mundo quieto. ──
    println!("       esperando a que el mundo termine de cargar (setBlocks estable 6s)...");
    let stable = wait_setblocks_quiescent(&mut s, &mut reader, &mut id, 6, 120);
    println!("       mundo cargado (setBlocks={stable} estable). Ahora puedes interactuar.\n");

    println!("   >>> COLOCA 5–6 escaleras de {VICTIM} en columnas distintas (cerca del spawn).");
    println!("   >>> Quédate quieto cuando termines. El harness ejecuta el barrido cuando detecte la huella.");
    println!("   >>> (esperando que coloques bloques — ledger.setBlocks deltas)\n");

    // Baseline POST-load: ahora un Δ≥5 sí refleja acción del usuario.
    let stats0_set = request(&mut s, &mut reader, id, "ledger.stats")["ok"]["setBlocks"]
        .as_i64().unwrap_or(stable);
    id += 1;
    let placed_delta = wait_set_blocks_delta(&mut s, &mut reader, &mut id, stats0_set, 5, 300);
    check(
        placed_delta >= 5,
        &format!("usuario colocó ≥5 bloques (ledger.setBlocks Δ={placed_delta} sobre base post-load={stats0_set})"),
    );
    println!("       … margen 20s para que termines de colocar mcwstairs específicamente");
    std::thread::sleep(std::time::Duration::from_secs(20));

    // ── Diag pieza 2: ¿se está incrementando byChunk desde onSetBlock? ──
    let diag = request(&mut s, &mut reader, id, "ledger.stats");
    id += 1;
    let d = &diag["ok"];
    println!(
        "       diag pieza 2: setBlocks={} byChunkInc={} byChunkDec={} \
         prevNull={} prevSame={} nsNull={} nsEqual={} bothVanilla={} \
         moddedBlocks={} getBlock={}",
        d["setBlocks"], d["byChunkInc"], d["byChunkDec"],
        d["setBlockPrevNull"], d["setBlockPrevSame"],
        d["setBlockNsNull"], d["setBlockNsEqual"],
        d["setBlockBothVanilla"], d["moddedBlocks"], d["getBlockMethodSig"]
    );

    // ── Snapshot PRE-sweep de wcg.counts: con pieza 2, byChunk se ha incrementado
    //    en vivo desde cada onSetBlock del usuario → counts.blocks>0 sin esperar a save.
    //    Esta es la primera prueba de que el agregado in-vivo funciona. ──
    let pre = request_p(&mut s, &mut reader, id, "wcg.counts", serde_json::json!({ "ns": VICTIM }));
    id += 1;
    let pre_blocks = pre["ok"]["blocks"].as_i64().unwrap_or(0);
    let pre_chunks = pre["ok"]["chunks"].as_i64().unwrap_or(0);
    println!("       wcg.counts PRE-sweep: blocks={pre_blocks} chunks={pre_chunks} (pieza 2: byChunk in-vivo)");
    check(
        pre_blocks > 0,
        &format!("PRE-sweep: byChunk in-vivo detectó la huella sin save previo (blocks={pre_blocks})"),
    );

    let restore_path = world.join("mksa").join("restore").join(format!(
        "tier1-2-{}.mksar",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0)
    ));
    let restore_path_str = restore_path.to_string_lossy().into_owned();

    // ── DryRun: ahora sin save previo, byChunk in-vivo localiza los chunks ──
    println!("[tier1-2] dryRun (sin mutación)");
    let dry = request_p(
        &mut s,
        &mut reader,
        id,
        "tx.tier1Sweep",
        serde_json::json!({ "ns": VICTIM, "dryRun": true }),
    );
    id += 1;
    let outcome_dry = capture_or_close(&dry, pid, &mut s, &mut reader, &mut id, instancia);
    let ok_dry = &outcome_dry["ok"];
    let would = ok_dry["wouldSweep"].as_i64().unwrap_or(0);
    let chunks_dry = ok_dry["chunksAffected"].as_i64().unwrap_or(0);
    let dirtied_dry = ok_dry["dirtied"].as_i64().unwrap_or(-1);
    println!(
        "       dryRun: chunksAffected={chunks_dry} wouldSweep={would} dirtied={dirtied_dry} thread={}",
        ok_dry["thread"]
    );
    check(
        would > 0,
        &format!("dryRun: byChunk in-vivo localiza {would} bloques sin save previo (corte 2)"),
    );
    check(chunks_dry >= 1, &format!("dryRun: al menos 1 chunk afectado ({chunks_dry})"));
    check(dirtied_dry == 0, &format!("dryRun: sin setBlock dummies (dirtied={dirtied_dry})"));
    check(
        ok_dry["onServerThread"].as_bool().unwrap_or(false),
        "dryRun corrió en el hilo del servidor",
    );

    // ── Sweep real: captura quirúrgica (copyOf+write) + barrido + re-escaneo ──
    println!("[tier1-2] sweep real: copyOf().write() in-memory → captura → barrido");
    println!("          restore file: {restore_path_str}");
    let sweep = request_p(
        &mut s,
        &mut reader,
        id,
        "tx.tier1Sweep",
        serde_json::json!({ "ns": VICTIM, "restorePath": &restore_path_str, "dryRun": false }),
    );
    id += 1;
    let outcome = capture_or_close(&sweep, pid, &mut s, &mut reader, &mut id, instancia);
    let ok = &outcome["ok"];
    let captured = ok["capturedBlocks"].as_i64().unwrap_or(0);
    let swept = ok["sweptBlocks"].as_i64().unwrap_or(0);
    let residual = ok["residualBlocks"].as_i64().unwrap_or(0);
    let chunks = ok["chunksAffected"].as_i64().unwrap_or(0);
    let sweep_errors = ok["sweepErrors"].as_i64().unwrap_or(0);
    let aggregate_stale = ok["aggregateStale"].as_bool().unwrap_or(true);
    let dirtied = ok["dirtied"].as_i64().unwrap_or(-1);
    let copy_of = ok["copyOfCalls"].as_i64().unwrap_or(0);
    let copy_of_null = ok["copyOfNull"].as_i64().unwrap_or(0);
    let writes_triggered = ok["writesTriggered"].as_i64().unwrap_or(0);
    let chunks_probed = ok["chunksProbed"].as_i64().unwrap_or(0);
    let written_delta = ok["writtenDelta"].as_i64().unwrap_or(0);
    println!(
        "       sweep: captured={captured} swept={swept} residual={residual} \
         chunksAffected={chunks} sweepErrors={sweep_errors} \
         aggregateStale={aggregate_stale} dirtied={dirtied} thread={}",
        ok["thread"]
    );
    println!(
        "       captura quirúrgica: chunksProbed={chunks_probed} copyOfCalls={copy_of} \
         copyOfNull={copy_of_null} writesTriggered={writes_triggered} writtenDelta={written_delta}"
    );
    println!(
        "       diag SCAN: calls={} ok={} fails={} armedNsHits={}",
        ok["scanCalls"], ok["scanOk"], ok["scanFails"], ok["scanArmedNsHits"]
    );
    println!(
        "       diag CAPTURE: calls={} palettesWithArmedNs={}",
        ok["captureCalls"], ok["capturePalettesWithArmedNs"]
    );

    check(
        ok["onServerThread"].as_bool().unwrap_or(false),
        "el barrido corrió en el hilo del servidor",
    );
    check(captured > 0, &format!("captura quirúrgica: capturedBlocks={captured} > 0 (sin saveAllChunks)"));
    check(swept > 0, &format!("barrido en vivo: sweptBlocks={swept} > 0"));
    check(residual == 0, &format!("invariante post-sweep: residualBlocks=0 (medido {residual})"));
    check(chunks >= 1, &format!("chunksAffected={chunks} ≥ 1"));
    // Pieza 2: byChunk se actualiza desde onSetBlock con el prevState → byChunk
    // refleja la realidad in-vivo sin esperar a scan() post-save. La deuda
    // aggregateStale del corte 1 queda pagada.
    check(!aggregate_stale, "aggregateStale=false (pieza 2: byChunk in-vivo desde onSetBlock)");
    check(sweep_errors == 0, &format!("sin errores de setBlock (sweepErrors={sweep_errors})"));
    // Pieza 1: captura quirúrgica iterando SOLO chunks víctima (de byChunk). copyOf
    // retorna null para chunks no-dirty; reintentamos forzando dirty con UN setBlock
    // por chunk afectado. `dirtied` es proporcional a la huella (típicamente 1-10),
    // muy lejos de los 867 del corte 1. dirtied=0 = todos estaban dirty naturalmente.
    check(
        dirtied <= chunks * 2,
        &format!("dirtied proporcional a la huella (dirtied={dirtied}, chunks víctima={chunks}, corte 1 hacía 867)"),
    );
    check(copy_of > 0, &format!("copyOf().write() invocado ({copy_of}× chunks reserializados in-memory)"));
    check(
        writes_triggered >= copy_of,
        &format!("writesTriggered={writes_triggered} ≥ copyOfCalls={copy_of} (cada SCD.write() dispara el hook)"),
    );

    // ── Snapshot POST-sweep de wcg.counts: byChunk decrementado por onSetBlock
    //    de cada celda barrida (prevState=mcwstairs, newState=air) → blocks debe ser 0.
    //    Esta es la prueba dura de que la pieza 2 funciona en sentido decremental. ──
    let post = request_p(&mut s, &mut reader, id, "wcg.counts", serde_json::json!({ "ns": VICTIM }));
    id += 1;
    let post_blocks = post["ok"]["blocks"].as_i64().unwrap_or(-1);
    let post_chunks = post["ok"]["chunks"].as_i64().unwrap_or(-1);
    println!(
        "       wcg.counts POST-sweep: blocks={post_blocks} chunks={post_chunks} \
         (Δ={} respecto a PRE-sweep)",
        post_blocks - pre_blocks
    );
    check(
        post_blocks == 0,
        &format!("POST-sweep: byChunk in-vivo refleja 0 presencia (blocks={post_blocks})"),
    );
    check(
        post_chunks == 0,
        &format!("POST-sweep: 0 chunks con la víctima (chunks={post_chunks})"),
    );

    // ── Cierre directo: el sweep ya terminó, no esperamos más actividad. El quiesce
    //    post-sweep del corte 1 disparaba ConnectionReset si MC cerraba la conexión
    //    socket antes (visto en el intento 2). Cierre tolerante a EOF: no más requests. ──
    println!("  … sweep completado; cierro juego sin esperar más actividad");
    drop(reader);
    drop(s);
    std::thread::sleep(std::time::Duration::from_secs(2));
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    // ── Restore file: existe y tiene tamaño > cabecera ──
    let meta = std::fs::metadata(&restore_path);
    check(meta.is_ok(), &format!("restore file existe en disco ({restore_path_str})"));
    let size = meta.map(|m| m.len()).unwrap_or(0);
    check(size > 40, &format!("restore file tiene tamaño > cabecera ({size} bytes)"));

    println!("\n   ═══ VEREDICTO TIER 1 CORTE 2 ═══");
    println!("   Captura quirúrgica zero-IO: {copy_of} chunks reserializados in-memory");
    println!("   vía SerializableChunkData.copyOf(level, chunk).write() — sin tocar disco,");
    println!("   sin tick lag, sin reescribir level.dat/playerdata. byChunk se mantiene");
    println!("   in-vivo desde onSetBlock con el prevState (hook al ARETURN, pieza 2):");
    println!("   wcg.counts pasó de {pre_blocks} a {post_blocks} sin esperar a scan().");
    println!("   Las dos deudas técnicas del corte 1 quedan pagadas.\n");

    // Historial para inspección manual (mismo destino que corte 1).
    let history = world.join("mksa").join("restore").join("historial");
    if !history.exists() {
        let _ = std::fs::create_dir_all(&history);
    }
    if let Some(name) = restore_path.file_name() {
        let _ = std::fs::rename(&restore_path, history.join(name));
    }
}

/// Devuelve el `Value` si la respuesta es `ok`; si no, cierra el juego limpiamente y
/// aborta con un mensaje informativo (igual que `inproc1_check`: nunca dejar Minecraft
/// huérfano en caso de fallo del agente).
fn capture_or_close(
    r: &serde_json::Value,
    pid: u32,
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: &mut u64,
    instancia: &str,
) -> serde_json::Value {
    if r["ok"].is_object() {
        return r.clone();
    }
    let code = r["err"]["code"].as_str().unwrap_or("?").to_string();
    let msg = r["err"]["msg"].as_str().unwrap_or("").to_string();
    quiesce(s, reader, id);
    kill_pid(pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));
    check(false, &format!("tx.tier1Sweep falló: {code} — {msg}"));
    unreachable!()
}

// ─────────────────────────── Fase 3, corte 1: relaunch T0 ───────────────────────────

/// Fase 3, corte 1: ¿es viable una transición controlada entre dos configuraciones
/// de mods sobre el MISMO mundo, vía dos JVMs? No prueba que desactivar un T0 sea
/// útil — prueba que el puente entre dos JVMs existe y funciona. Sin posición, sin
/// inventario, sin estado del jugador.
///
///   A. Lanza el modset completo, lee state.mods + state.epoch, elige un mod T0
///      hoja (solo-datos, sin dependientes) con jar aislable.
///   relaunch. relaunch_disable cierra A con gracia (suelta el session.lock),
///      mueve el jar fuera de mods/, lanza B sobre el mismo mundo.
///   B. Confirma que B NO ve el mod, que la firma del modset cambió, que el mundo
///      de A cargó intacto en B (parse() corrió) y que el disco no se corrompió.
/// Restaura el jar al final: la instancia queda exactamente como estaba.
fn fase3_relaunch_check(instancia: &str) {
    println!("[fase3] corte 1: relaunch T0 — transición entre dos modsets sobre el mismo mundo");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let region_dir = Path::new(&inst.minecraft_dir)
        .join("saves")
        .join("mksa-test")
        .join("region");

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();

    // ── A: configuración original ──
    println!("[fase3] lanzamiento A (modset completo)");
    let (pid_a, mut s, mut reader) = open_session(&handle, &games, instancia);
    let mods = request(&mut s, &mut reader, 2, "state.mods");
    let ep_a = request(&mut s, &mut reader, 3, "state.epoch");
    let hash_a = ep_a["ok"]["hash"].as_str().unwrap_or("").to_string();
    check(!hash_a.is_empty(), &format!("A: época real (hash={hash_a})"));
    let all = mods["ok"]["mods"].as_array().cloned().unwrap_or_default();

    // Reposo antes de elegir y cerrar: con el mundo en reposo el agregado del WCG
    // ya está poblado (los chunks del spawn se serializaron), así que wcg.counts da
    // su observación más fuerte. Luego soltamos la sesión: el cierre real lo hace
    // relaunch_disable.
    let mut id = 4u64;
    quiesce(&mut s, &mut reader, &mut id);

    let (victim_id, victim_jars) = pick_victim(&all, &mut s, &mut reader, &mut id);
    check(
        all.iter().any(|m| m["id"].as_str() == Some(victim_id.as_str())),
        &format!("A ve el mod a desactivar: {victim_id}"),
    );
    println!("       víctima: {victim_id} ({} jar en disco)", victim_jars.len());
    for j in &victim_jars {
        println!("         {j}");
    }

    drop(reader);
    drop(s);

    // ── relaunch: cierra A, desactiva el mod, lanza B sobre el mismo mundo ──
    println!("[fase3] relaunch_disable: cerrar A → mover jar fuera de mods/ → lanzar B");
    let result_b = launch::relaunch_disable(
        handle.clone(),
        games.clone(),
        instancia.into(),
        victim_jars.clone(),
    )
    .expect("relaunch_disable");
    check(!result_b.already_running, &format!("B lanzado, pid {}", result_b.pid));
    check(!launch::pid_alive(pid_a), "A se cerró: mundo liberado (session.lock libre)");
    check(
        !victim_jars.iter().any(|j| Path::new(j).is_file()),
        "el jar del mod T0 salió de mods/",
    );

    // ── B: configuración nueva ──
    println!("[fase3] enganche a B (modset sin {victim_id})");
    let (mut s2, mut reader2) = attach_session(&games, instancia);
    let mods_b = request(&mut s2, &mut reader2, 2, "state.mods");
    let ep_b = request(&mut s2, &mut reader2, 3, "state.epoch");
    let hash_b = ep_b["ok"]["hash"].as_str().unwrap_or("").to_string();
    let all_b = mods_b["ok"]["mods"].as_array().cloned().unwrap_or_default();

    check(
        !all_b.iter().any(|m| m["id"].as_str() == Some(victim_id.as_str())),
        &format!("B NO ve el mod {victim_id}: la transición se aplicó"),
    );
    check(
        !hash_b.is_empty() && hash_b != hash_a,
        &format!("B: firma del modset cambió (hash_a={hash_a} → hash_b={hash_b})"),
    );

    // El mundo de A cargó en B sin corromperse: parse() deserializó sus chunks.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    let read_total = loop {
        let (_, _, rt) = ledger_stats(&mut s2, &mut reader2, id);
        id += 1;
        if rt > 0 {
            break rt;
        }
        if std::time::Instant::now() >= deadline {
            check(false, "B leyó chunks del mundo de A (readTotal>0) en 2.5 min");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };
    check(
        read_total > 0,
        &format!("B cargó el mundo de A intacto: {read_total} chunks deserializados"),
    );

    // Reposo + cierre limpio de B; luego auditoría estructural en disco.
    quiesce(&mut s2, &mut reader2, &mut id);
    drop(reader2);
    drop(s2);
    launch::close_game_gracefully(result_b.pid);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    check(
        region_chunks_intact(&region_dir),
        "disco: 0 corrupción (todo chunk conserva 'xPos')",
    );

    // Limpieza: devolver el jar a mods/ (la instancia queda como estaba).
    restore_victim(instancia, &victim_jars);
    check(
        victim_jars.iter().all(|j| Path::new(j).is_file()),
        "jar del mod restaurado a mods/ (instancia intacta)",
    );
}

/// Fase 3, corte 2: víctima CON huella real en el mundo. Es **observacional** — no
/// asevera un resultado, lo *mide*. La incógnita (centrada en MKSA): ¿el relaunch
/// necesita saneamiento por ledger para ser reversible? Forma operativa: tras B
/// reescribir el mundo SIN el mod, ¿sobrevive la huella de la víctima en disco?
///
/// El detector de reescritura es el **sello de época** (`mksa:prov.epoch`, §3.2):
/// el write-hook lo estampa en cada chunk que B serializa, y `epoch_B != epoch_A`.
/// La observación es **per-chunk** (no per-región): un `epoch_B` en otro chunk no
/// prueba nada sobre el chunk de la huella. Como un B destructivo borra la evidencia
/// de que el chunk *tuvo* la huella, se fija una **muestra** con un snapshot ANTES
/// (A muerta, B sin escribir) y se relee DESPUÉS.
///
/// Veredicto por chunk de la muestra (el epoch demuestra ESCRITURA, no lectura):
///   epoch_B + ns presente → A  (reserializó y CONSERVÓ)
///   epoch_B + ns ausente  → A' (reserializó y DESCARTÓ) — invalida reversibilidad
///   epoch_A + ns presente → inconcluso (sin evidencia de que B reserializara ESTE chunk)
///
/// LÍMITES (ver proyecto.md): NO demuestra reversibilidad, sino conservación/pérdida
/// observable de la huella; la presencia del namespace es un *proxy* de "persistió".
fn fase3_relaunch2_check(instancia: &str) {
    println!("[fase3-2] corte 2: víctima CON huella real — ¿B conserva (A), descarta (A') o no reserializa la huella?");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let region_dir = Path::new(&inst.minecraft_dir)
        .join("saves")
        .join("mksa-test")
        .join("region");

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();

    const VICTIM: &str = "mcwstairs";
    const NS: &[u8] = b"mcwstairs";

    // ── A: modset completo ──
    println!("[fase3-2] lanzamiento A (modset completo)");
    let (pid_a, mut s, mut reader) = open_session(&handle, &games, instancia);
    let mods = request(&mut s, &mut reader, 2, "state.mods");
    let all = mods["ok"]["mods"].as_array().cloned().unwrap_or_default();

    // Víctima fija: mcwstairs. Se valida que siga siendo aislable (hoja, jar en
    // disco, no compartido, no re-provista jar-in-jar) con la misma política del
    // corte 1 — pero NO se elige por WCG: aquí queremos huella, no ausencia.
    let victim_jars = validate_fixed_victim(&all, VICTIM);
    check(!victim_jars.is_empty(), &format!("A ve la víctima fija '{VICTIM}' con jar aislable"));
    println!("       víctima fija: {VICTIM} ({} jar)", victim_jars.len());
    for j in &victim_jars {
        println!("         {j}");
    }

    // Huella: intervención manual SOLO en A. Esperamos a observar wcg.counts>0.
    let mut id = 3u64;
    println!("\n   >>> COLOCA varias escaleras de {VICTIM} en el mundo y luego quédate quieto.");
    println!("   >>> NO salgas del juego — el harness cierra A y lanza B por su cuenta.");
    println!("   >>> (esperando wcg.counts{{{VICTIM}}}.blocks > 0 …)\n");
    let count_a = wait_counts_positive(&mut s, &mut reader, &mut id, VICTIM, 300);
    check(count_a > 0, &format!("A: huella colocada — wcg.counts{{{VICTIM}}}.blocks={count_a}"));

    let ep_a = request(&mut s, &mut reader, id, "state.epoch");
    id += 1;
    let epoch_a = ep_a["ok"]["epoch"].as_i64().unwrap_or(0);
    check(epoch_a != 0, &format!("A: epoch_A={epoch_a}"));

    // Reposo, y cierre limpio de A POR EL HARNESS (abre la ventana del snapshot
    // ANTES: A muerta, B sin lanzar → el .mca se lee sin lock ni escritor de B).
    quiesce(&mut s, &mut reader, &mut id);
    drop(reader);
    drop(s);
    launch::close_game_gracefully(pid_a);
    check(wait_pid_dead(pid_a, 60), "A cerró (mundo liberado) antes del snapshot ANTES");

    // ── Snapshot ANTES: la muestra son los chunks con huella, definidos por CONTENIDO ──
    let before = scan_region_chunks(&region_dir, NS);
    let mut sample: Vec<(i32, i32)> =
        before.iter().filter(|(_, (_, has))| *has).map(|(k, _)| *k).collect();
    sample.sort();
    check(
        !sample.is_empty(),
        &format!("snapshot ANTES: {} chunk(s) con huella de {VICTIM}", sample.len()),
    );
    for (cx, cz) in &sample {
        println!("       ANTES ({cx},{cz}) epoch={:?} ns=sí", before[&(*cx, *cz)].0);
    }

    // ── relaunch: cierre no-op (A ya muerta), stage-out, lanza B ──
    println!("[fase3-2] relaunch_disable: desactivar {VICTIM} → lanzar B");
    let result_b = launch::relaunch_disable(
        handle.clone(),
        games.clone(),
        instancia.into(),
        victim_jars.clone(),
    )
    .expect("relaunch_disable");
    check(!result_b.already_running, &format!("B lanzado, pid {}", result_b.pid));
    check(
        !victim_jars.iter().any(|j| Path::new(j).is_file()),
        "el jar de la víctima salió de mods/",
    );

    // ── B corre de forma natural (sin intervención humana en B) ──
    println!("[fase3-2] enganche a B (modset sin {VICTIM})");
    let (mut s2, mut reader2) = attach_session(&games, instancia);
    let mods_b = request(&mut s2, &mut reader2, 2, "state.mods");
    let all_b = mods_b["ok"]["mods"].as_array().cloned().unwrap_or_default();
    check(
        !all_b.iter().any(|m| m["id"].as_str() == Some(VICTIM)),
        &format!("B NO ve {VICTIM}: la transición se aplicó"),
    );
    let ep_b = request(&mut s2, &mut reader2, 3, "state.epoch");
    let epoch_b = ep_b["ok"]["epoch"].as_i64().unwrap_or(0);
    check(
        epoch_b != 0 && epoch_b != epoch_a,
        &format!("B: epoch_B={epoch_b} (≠ epoch_A={epoch_a})"),
    );

    // El mundo de A cargó en B (parse corrió).
    let mut id2 = 4u64;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    loop {
        let (_, _, rt) = ledger_stats(&mut s2, &mut reader2, id2);
        id2 += 1;
        if rt > 0 {
            break;
        }
        if std::time::Instant::now() >= deadline {
            check(false, "B leyó chunks del mundo de A (readTotal>0) en 2.5 min");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    }
    // Reposo + cierre limpio de B; registramos written_B como actividad de escritura.
    quiesce(&mut s2, &mut reader2, &mut id2);
    let (written_b, _, _) = ledger_stats(&mut s2, &mut reader2, id2);
    drop(reader2);
    drop(s2);
    launch::close_game_gracefully(result_b.pid);
    check(wait_pid_dead(result_b.pid, 60), "B cerró limpio");
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    // Resultado B (catastrófico): corrupción estructural.
    check(region_chunks_intact(&region_dir), "disco: 0 corrupción (todo chunk conserva 'xPos')");

    // ── Snapshot DESPUÉS + veredicto per-chunk (OBSERVACIONAL, no es check) ──
    let after = scan_region_chunks(&region_dir, NS);
    let (mut n_a, mut n_ap, mut n_inc, mut n_missing) = (0u32, 0u32, 0u32, 0u32);
    println!("\n   ── veredicto per-chunk (epoch_A={epoch_a}, epoch_B={epoch_b}, written_B={written_b}) ──");
    for (cx, cz) in &sample {
        match after.get(&(*cx, *cz)) {
            None => {
                n_missing += 1;
                println!("       ({cx},{cz}): AUSENTE del .mca tras B (anómalo)");
            }
            Some((ep, has)) => {
                let reser = *ep == Some(epoch_b);
                let tag = if reser && *has {
                    n_a += 1;
                    "A  (B reserializó y CONSERVÓ la huella)"
                } else if reser && !*has {
                    n_ap += 1;
                    "A' (B reserializó y DESCARTÓ la huella)"
                } else if *ep == Some(epoch_a) {
                    n_inc += 1;
                    "inconcluso (sin evidencia de que B reserializara este chunk)"
                } else {
                    n_inc += 1;
                    "inconcluso (epoch anómalo)"
                };
                println!(
                    "       ({cx},{cz}): epoch={:?} ns={} → {tag}",
                    ep,
                    if *has { "sí" } else { "no" }
                );
            }
        }
    }
    println!(
        "   ── resumen: A={n_a}  A'={n_ap}  inconcluso={n_inc}  ausente={n_missing}  (muestra={}) ──",
        sample.len()
    );
    let verdict = if n_ap > 0 {
        "A'  — REVERSIBILIDAD COMPROMETIDA: el ledger pasa de optimización a candidato a REQUISITO (§3.8/§3.12)"
    } else if n_a > 0 {
        "A   — huella conservada tras la reserialización de B (en este camino el ledger es optimización, no requisito)"
    } else {
        "INCONCLUSO — B no reserializó los chunks de la muestra (epoch_A); siguiente paso: forzar reescritura o mejorar la observación"
    };
    println!("\n   ═══ VEREDICTO CORTE 2: {verdict} ═══\n");

    // Limpieza: instancia como estaba.
    restore_victim(instancia, &victim_jars);
    check(
        victim_jars.iter().all(|j| Path::new(j).is_file()),
        "jar de la víctima restaurado a mods/ (instancia intacta)",
    );
}

/// Fase 3, corte 2b: la huella de la víctima vive en el **inventario del jugador**
/// (un ítem de `mcwstairs` cogido, NO colocado), no en un chunk. Variante del corte 2
/// que pregunta lo mismo sobre otra superficie de serialización: tras B reescribir el
/// mundo SIN el mod, ¿el `playerdata` conserva la huella, la descarta al reescribir, o
/// B ni siquiera lo reescribe?
///
/// DIFERENCIAS con el corte 2 (que justifican un modo aparte):
///   1. **Trigger por tiempo, no por `wcg.counts`.** Un ítem en el inventario no es un
///      bloque colocado → `wcg.counts{ns}.blocks` se queda en 0 y `wait_counts_positive`
///      colgaría. Se abre una ventana fija (`MKSA_INV_WAIT_SECS`, def. 40s) para coger
///      el ítem; el cierre limpio de A persiste el inventario a disco ("Saving players").
///   2. **Detector de reescritura = hash del NBT, no sello de época.** El hook del
///      ledger solo instrumenta `SerializableChunkData` (chunks); el playerdata NO lleva
///      `mksa:prov.epoch` (§3.10 lo prevé para jugadores, pero no está implementado). Así
///      que "B reescribió el .dat" se infiere de que su contenido cambió. Eso es justo
///      el discriminador **memoria vs escritura**: hash igual + huella presente ⇒ B no
///      persistió el descarte (pérdida, si la hubo, solo en memoria); hash distinto +
///      huella ausente ⇒ pérdida DURANTE la escritura (análogo a A').
///   3. **Muestra aislada del ruido de plantilla.** La plantilla trae un `playerdata` de
///      OTRO jugador (UUID distinto) que B nunca logea. La muestra = el `.dat` que aparece
///      tras A y NO existe en la plantilla → el jugador real del run, sin falsos positivos.
///
/// LÍMITE (igual que el corte 2): mide conservación/pérdida observable de la huella, NO
/// reversibilidad. La presencia del namespace es un proxy de "persistió la contribución".
fn fase3_inv_check(instancia: &str) {
    println!("[fase3-inv] corte 2b: huella en el INVENTARIO — ¿B conserva (A), descarta al reescribir (A') o no reescribe el playerdata?");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let world = saves.join("mksa-test");
    let template = saves.join("mksa-test-template");

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();

    const VICTIM: &str = "mcwstairs";
    const NS: &[u8] = b"mcwstairs";

    // Baseline: nombres de playerdata que YA trae la plantilla (jugador que B nunca
    // logea) → se excluyen de la muestra para que no contaminen la huella del run.
    let template_names = playerdata_names(&template.join("playerdata"));

    // ── A: modset completo ──
    println!("[fase3-inv] lanzamiento A (modset completo)");
    let (pid_a, mut s, mut reader) = open_session(&handle, &games, instancia);
    let mods = request(&mut s, &mut reader, 2, "state.mods");
    let all = mods["ok"]["mods"].as_array().cloned().unwrap_or_default();
    let victim_jars = validate_fixed_victim(&all, VICTIM);
    check(!victim_jars.is_empty(), &format!("A ve la víctima fija '{VICTIM}' con jar aislable"));
    for j in &victim_jars {
        println!("         {j}");
    }

    let ep_a = request(&mut s, &mut reader, 3, "state.epoch");
    let epoch_a = ep_a["ok"]["epoch"].as_i64().unwrap_or(0);
    check(epoch_a != 0, &format!("A: epoch_A={epoch_a}"));

    // Trigger por tiempo (ver doc de la fn). El cierre limpio de A guarda el inventario.
    let wait_secs: u64 = std::env::var("MKSA_INV_WAIT_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(40);
    println!("\n   >>> COGE en tu inventario UN ítem de {VICTIM} (p.ej. una escalera del menú creativo).");
    println!("   >>> NO lo coloques en el mundo. Cierra el inventario y quédate quieto DENTRO del mundo.");
    println!("   >>> Tienes ~{wait_secs}s; al agotarse, el harness cierra A (guarda el playerdata) y lanza B.\n");
    let start = std::time::Instant::now();
    loop {
        let elapsed = start.elapsed().as_secs();
        if elapsed >= wait_secs {
            break;
        }
        let left = wait_secs - elapsed;
        if left % 10 == 0 {
            println!("       … {left}s para que el harness cierre A");
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }

    // Reposo + cierre limpio de A POR EL HARNESS (abre la ventana del snapshot ANTES).
    let mut id = 4u64;
    quiesce(&mut s, &mut reader, &mut id);
    drop(reader);
    drop(s);
    launch::close_game_gracefully(pid_a);
    check(wait_pid_dead(pid_a, 60), "A cerró (playerdata guardado) antes del snapshot ANTES");

    // ── Snapshot ANTES: la muestra = playerdata del jugador del run (no en plantilla) con huella ──
    let before = scan_playerdata(&world, NS);
    for (name, (has, _)) in &before {
        if *has && template_names.contains(name) {
            println!("       (excluido) {name}: huella de plantilla, jugador que B no logea → ruido");
        }
    }
    let mut sample: Vec<String> = before
        .iter()
        .filter(|(name, (has, _))| *has && !template_names.contains(*name))
        .map(|(n, _)| n.clone())
        .collect();
    sample.sort();
    check(
        !sample.is_empty(),
        &format!("snapshot ANTES: {} playerdata con huella de {VICTIM} (jugador del run)", sample.len()),
    );
    for n in &sample {
        println!("       ANTES {n}: ns=sí hash={:016x}", before[n].1);
    }

    // ── relaunch: cierre no-op (A ya muerta), stage-out, lanza B ──
    println!("[fase3-inv] relaunch_disable: desactivar {VICTIM} → lanzar B");
    let result_b = launch::relaunch_disable(
        handle.clone(),
        games.clone(),
        instancia.into(),
        victim_jars.clone(),
    )
    .expect("relaunch_disable");
    check(!result_b.already_running, &format!("B lanzado, pid {}", result_b.pid));
    check(
        !victim_jars.iter().any(|j| Path::new(j).is_file()),
        "el jar de la víctima salió de mods/",
    );

    // ── B corre natural (sin intervención humana) ──
    println!("[fase3-inv] enganche a B (modset sin {VICTIM})");
    let (mut s2, mut reader2) = attach_session(&games, instancia);
    let mods_b = request(&mut s2, &mut reader2, 2, "state.mods");
    let all_b = mods_b["ok"]["mods"].as_array().cloned().unwrap_or_default();
    check(
        !all_b.iter().any(|m| m["id"].as_str() == Some(VICTIM)),
        &format!("B NO ve {VICTIM}: la transición se aplicó"),
    );
    let ep_b = request(&mut s2, &mut reader2, 3, "state.epoch");
    let epoch_b = ep_b["ok"]["epoch"].as_i64().unwrap_or(0);
    check(
        epoch_b != 0 && epoch_b != epoch_a,
        &format!("B: epoch_B={epoch_b} (≠ epoch_A={epoch_a})"),
    );

    // El mundo de A cargó en B (parse corrió) — el playerdata se relee al logear.
    let mut id2 = 4u64;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    loop {
        let (_, _, rt) = ledger_stats(&mut s2, &mut reader2, id2);
        id2 += 1;
        if rt > 0 {
            break;
        }
        if std::time::Instant::now() >= deadline {
            check(false, "B leyó chunks del mundo de A (readTotal>0) en 2.5 min");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    }
    quiesce(&mut s2, &mut reader2, &mut id2);
    drop(reader2);
    drop(s2);
    launch::close_game_gracefully(result_b.pid);
    check(wait_pid_dead(result_b.pid, 60), "B cerró limpio");
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    // ── Snapshot DESPUÉS + veredicto por playerdata (OBSERVACIONAL) ──
    let after = scan_playerdata(&world, NS);
    let (mut n_a, mut n_ap, mut n_mem, mut n_anom, mut n_missing) = (0u32, 0u32, 0u32, 0u32, 0u32);
    println!("\n   ── veredicto por playerdata (sin sello de época: reescritura = cambio de hash del NBT; epoch_A={epoch_a}, epoch_B={epoch_b}) ──");
    for name in &sample {
        let (_, hash_a) = before[name];
        match after.get(name) {
            None => {
                n_missing += 1;
                println!("       {name}: AUSENTE del disco tras B (anómalo)");
            }
            Some((has_b, hash_b)) => {
                let rewritten = *hash_b != hash_a;
                let tag = if rewritten && !*has_b {
                    n_ap += 1;
                    "A' (B reescribió el playerdata y DESCARTÓ la huella) — pérdida durante la ESCRITURA"
                } else if rewritten && *has_b {
                    n_a += 1;
                    "A  (B reescribió y CONSERVÓ la huella)"
                } else if !rewritten && *has_b {
                    n_mem += 1;
                    "B NO reescribió el playerdata — la huella PERSISTE en disco (pérdida, si la hubo, solo en memoria/vista)"
                } else {
                    n_anom += 1;
                    "anómalo (hash igual pero ns ausente — imposible salvo bug)"
                };
                println!(
                    "       {name}: reescrito={} ns={} → {tag}",
                    rewritten,
                    if *has_b { "sí" } else { "no" }
                );
            }
        }
    }
    println!(
        "   ── resumen: A={n_a}  A'={n_ap}  solo-memoria={n_mem}  anómalo={n_anom}  ausente={n_missing}  (muestra={}) ──",
        sample.len()
    );
    let verdict = if n_ap > 0 {
        "A'  — el playerdata sufre el MISMO destino que los chunks: pérdida en escritura. El saneamiento por ledger antes de cerrar A debe cubrir TAMBIÉN al jugador (§3.10 'igual para jugadores' pasa de diseño a candidato a REQUISITO)"
    } else if n_a > 0 {
        "A   — B reescribió el playerdata pero CONSERVÓ la huella del ítem (en este eje el ledger es optimización, no requisito)"
    } else if n_mem > 0 {
        "SOLO-MEMORIA — B no reescribió el playerdata; el ítem sigue en disco. El descarte que B muestra al logear NO se persiste por esta vía — la pérdida del corte 2 (chunks) no se reproduce en el inventario"
    } else {
        "INCONCLUSO — sin reescritura observable del playerdata del jugador del run"
    };
    println!("\n   ═══ VEREDICTO CORTE 2b (inventario): {verdict} ═══\n");

    // Limpieza: instancia como estaba.
    restore_victim(instancia, &victim_jars);
    check(
        victim_jars.iter().all(|j| Path::new(j).is_file()),
        "jar de la víctima restaurado a mods/ (instancia intacta)",
    );
}

/// Nombres de los `*.dat` (no `.dat_old`) de un directorio `playerdata` — para aislar
/// el jugador del run del ruido de la plantilla.
/// Fase 3, corte 3: **estreno del ledger como MECANISMO** (§3.14). El barrido
/// dirigido por el ledger, armado en A ANTES de cerrar, captura la huella de la
/// víctima —bloques en chunks Y items en playerdata— a un archivo de restauración
/// con NBT completo (§7, interim binario). Luego B reescribe el mundo sin el mod y
/// DESTRUYE la huella (corte 2 = A'); se comprueba que el restore file SOBREVIVE
/// intacto: la única copia de lo que el relaunch borra. Resultado: la reversibilidad
/// pasa de imposible (corte 2) a POSIBLE. La re-inyección efectiva es el corte 4.
///
/// Alcance (acordado): SOLO captura + suficiencia. No re-inyecta. Mecanismo:
/// piggyback en el save de cierre (sin handle vivo; el agente solo actúa por el
/// bytecode ya inyectado + el hook nuevo de NbtIo.writeCompressed).
fn fase3_sweep_check(instancia: &str) {
    println!("[fase3-sweep] corte 3: barrido dirigido por el ledger en A — captura la huella (chunks + playerdata) al archivo de restauración antes de cerrar");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let world = saves.join("mksa-test");
    let region_dir = world.join("region");
    let template = saves.join("mksa-test-template");
    let template_names = playerdata_names(&template.join("playerdata"));

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();

    const VICTIM: &str = "mcwstairs";
    const NS: &[u8] = b"mcwstairs";

    // ── A: modset completo ──
    println!("[fase3-sweep] lanzamiento A (modset completo)");
    let (pid_a, mut s, mut reader) = open_session(&handle, &games, instancia);
    let mods = request(&mut s, &mut reader, 2, "state.mods");
    let all = mods["ok"]["mods"].as_array().cloned().unwrap_or_default();
    let victim_jars = validate_fixed_victim(&all, VICTIM);
    check(!victim_jars.is_empty(), &format!("A ve la víctima fija '{VICTIM}' con jar aislable"));
    for j in &victim_jars {
        println!("         {j}");
    }

    // Huella en AMBAS superficies: bloques colocados + un ítem en la mochila.
    let mut id = 3u64;
    println!("\n   >>> COLOCA varias escaleras de {VICTIM} en el mundo Y deja UN ítem de {VICTIM} en tu mochila (sin colocarlo).");
    println!("   >>> Luego quédate quieto DENTRO del mundo. El harness arma el barrido y cierra A por su cuenta.");
    println!("   >>> (esperando wcg.counts{{{VICTIM}}}.blocks > 0 …)\n");
    let count_a = wait_counts_positive(&mut s, &mut reader, &mut id, VICTIM, 300);
    check(count_a > 0, &format!("A: huella de bloques colocada — wcg.counts{{{VICTIM}}}.blocks={count_a}"));

    let ep_a = request(&mut s, &mut reader, id, "state.epoch");
    id += 1;
    let epoch_a = ep_a["ok"]["epoch"].as_i64().unwrap_or(0);
    check(epoch_a != 0, &format!("A: epoch_A={epoch_a}"));

    // ── ARMAR el barrido (antes del quiesce: así lo captura cualquier save posterior,
    //    sea autosave durante el reposo o el save de cierre). El launcher provee la
    //    ruta del restore file: el agente no tiene handle al servidor para conocerla. ──
    let restore_path = world.join("mksa").join("restore").join(format!("tx-{:016x}.mksar", epoch_a as u64));
    let restore_path_str = restore_path.to_string_lossy().into_owned();
    println!("[fase3-sweep] armando barrido: ns={VICTIM} → {restore_path_str}");
    let armed = request_p(
        &mut s,
        &mut reader,
        id,
        "ledger.sweepArm",
        serde_json::json!({ "ns": VICTIM, "path": restore_path_str }),
    );
    id += 1;
    check(
        armed["ok"]["armed"].as_bool() == Some(true),
        &format!("barrido armado (ledger.sweepArm ok={})", armed["ok"]),
    );

    // Reposo + cierre limpio de A: el save de cierre dispara onChunkWrite (bloques) y
    // NbtIo.writeCompressed → onPlayerWrite (item) con el barrido armado → captura.
    quiesce(&mut s, &mut reader, &mut id);
    drop(reader);
    drop(s);
    launch::close_game_gracefully(pid_a);
    check(wait_pid_dead(pid_a, 60), "A cerró limpio (save de cierre = captura del barrido)");

    // ── Leer el archivo de restauración: la captura debe contener AMBAS superficies ──
    let cap = read_restore(&restore_path)
        .unwrap_or_else(|| { check(false, "el archivo de restauración existe y es legible"); unreachable!() });
    let blocks_ns = cap.blocks.iter().filter(|(_, _, _, n)| blob_has(n, NS)).count();
    let items_ns = cap.items.iter().filter(|(_, n)| blob_has(n, NS)).count();
    println!(
        "       restore: ns={} epoch={:x} bloques={} (con ns={}) items={} (con ns={})",
        cap.ns, cap.epoch as u64, cap.blocks.len(), blocks_ns, cap.items.len(), items_ns
    );
    check(cap.ns == VICTIM, &format!("restore: namespace de la víctima sellado ({})", cap.ns));
    check(!cap.blocks.is_empty(), "restore: capturó bloques de la víctima (superficie MUNDO)");
    check(blocks_ns == cap.blocks.len(), "restore: TODO bloque capturado lleva el namespace de la víctima (NBT fiel)");
    check(
        (cap.blocks.len() as i64) >= count_a,
        &format!("restore: cobertura de bloques completa ({} capturados ≥ {} vistos por wcg)", cap.blocks.len(), count_a),
    );
    check(!cap.items.is_empty(), "restore: capturó items de la víctima (superficie JUGADOR)");
    check(items_ns == cap.items.len(), "restore: TODO item capturado lleva el namespace de la víctima (NBT fiel)");
    let cap_blocks = cap.blocks.len();
    let cap_items = cap.items.len();

    // ── Snapshot ANTES de B: la huella que B va a destruir (corte 2 = A') ──
    let region_before = scan_region_chunks(&region_dir, NS);
    let world_sample: Vec<(i32, i32)> =
        region_before.iter().filter(|(_, (_, h))| *h).map(|(k, _)| *k).collect();
    let pd_before = scan_playerdata(&world, NS);
    let pd_sample: Vec<String> = pd_before
        .iter()
        .filter(|(n, (h, _))| *h && !template_names.contains(*n))
        .map(|(n, _)| n.clone())
        .collect();
    check(!world_sample.is_empty(), &format!("ANTES de B: {} chunk(s) con huella en disco", world_sample.len()));
    check(!pd_sample.is_empty(), &format!("ANTES de B: {} playerdata con huella en disco", pd_sample.len()));

    // ── relaunch B sin la víctima (cierre no-op: A ya muerta) ──
    println!("[fase3-sweep] relaunch_disable: desactivar {VICTIM} → lanzar B (reescribe el mundo SIN el mod)");
    let result_b = launch::relaunch_disable(
        handle.clone(),
        games.clone(),
        instancia.into(),
        victim_jars.clone(),
    )
    .expect("relaunch_disable");
    check(!result_b.already_running, &format!("B lanzado, pid {}", result_b.pid));

    let (mut s2, mut reader2) = attach_session(&games, instancia);
    let mods_b = request(&mut s2, &mut reader2, 2, "state.mods");
    let all_b = mods_b["ok"]["mods"].as_array().cloned().unwrap_or_default();
    check(
        !all_b.iter().any(|m| m["id"].as_str() == Some(VICTIM)),
        &format!("B NO ve {VICTIM}: la transición se aplicó"),
    );
    let ep_b = request(&mut s2, &mut reader2, 3, "state.epoch");
    let epoch_b = ep_b["ok"]["epoch"].as_i64().unwrap_or(0);
    check(epoch_b != 0 && epoch_b != epoch_a, &format!("B: epoch_B={epoch_b} (≠ epoch_A={epoch_a})"));

    // El mundo de A cargó en B (parse corrió) — fuerza la reescritura destructiva.
    let mut id2 = 4u64;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    loop {
        let (_, _, rt) = ledger_stats(&mut s2, &mut reader2, id2);
        id2 += 1;
        if rt > 0 {
            break;
        }
        if std::time::Instant::now() >= deadline {
            check(false, "B leyó chunks del mundo de A (readTotal>0) en 2.5 min");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    }
    quiesce(&mut s2, &mut reader2, &mut id2);
    drop(reader2);
    drop(s2);
    launch::close_game_gracefully(result_b.pid);
    check(wait_pid_dead(result_b.pid, 60), "B cerró limpio");
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    check(region_chunks_intact(&region_dir), "disco: 0 corrupción (todo chunk conserva 'xPos')");

    // ── Snapshot DESPUÉS de B: la huella en el MUNDO se perdió (corte 2 A', re-confirmado) ──
    let region_after = scan_region_chunks(&region_dir, NS);
    let world_lost = world_sample
        .iter()
        .all(|k| !region_after.get(k).map(|(_, h)| *h).unwrap_or(false));
    let pd_after = scan_playerdata(&world, NS);
    let pd_lost = pd_sample
        .iter()
        .all(|n| !pd_after.get(n).map(|(h, _)| *h).unwrap_or(false));
    check(world_lost, "DESPUÉS de B: la huella de bloques DESAPARECIÓ del .mca (A': relaunch destructivo)");
    check(pd_lost, "DESPUÉS de B: la huella del item DESAPARECIÓ del playerdata (A': relaunch destructivo)");

    // ── PAYOFF: el restore file SOBREVIVIÓ a B — la única copia de la huella ──
    let cap2 = read_restore(&restore_path)
        .unwrap_or_else(|| { check(false, "el restore file sigue legible tras B"); unreachable!() });
    check(
        cap2.blocks.len() == cap_blocks && cap2.items.len() == cap_items,
        &format!(
            "el restore file SOBREVIVIÓ intacto a B ({} bloques + {} items) — única copia de lo que el relaunch destruyó",
            cap2.blocks.len(), cap2.items.len()
        ),
    );

    println!("\n   ═══ VEREDICTO CORTE 3 ═══");
    println!("   El ledger actuó por PRIMERA VEZ como MECANISMO: dirigió un barrido en A que capturó la huella");
    println!("   de {VICTIM} ({cap_blocks} bloques + {cap_items} items, NBT completo) al archivo de restauración ANTES de cerrar.");
    println!("   B destruyó la huella en mundo y jugador (corte 2 = A', re-confirmado), pero el restore file SOBREVIVIÓ:");
    println!("   la reversibilidad pasa de IMPOSIBLE (corte 2) a POSIBLE. La re-inyección efectiva (consumir el");
    println!("   restore file con la política de conflicto §7) es el corte 4.\n");

    // Limpieza: instancia como estaba.
    restore_victim(instancia, &victim_jars);
    check(
        victim_jars.iter().all(|j| Path::new(j).is_file()),
        "jar de la víctima restaurado a mods/ (instancia intacta)",
    );
}

/// Fase 3, corte 4: **cierre del bucle de reversibilidad para bloques** (§3.16). El
/// ledger ESCRIBE al mundo por primera vez: tras A capturar la huella y B destruirla,
/// C la RE-INYECTA en el NBT del chunk al leerlo (parse), con su modset original que
/// resuelve el namespace. Mecanismo: inyección read-hook (sin handle vivo), simétrico
/// inverso de la captura. Alcance: solo BLOQUES (items = corte 4b).
///
/// Encadena A→B→C en una corrida (captura y restauración son la misma versión de código).
fn fase3_restore_check(instancia: &str) {
    println!("[fase3-restore] corte 4: re-inyección de bloques — A captura, B destruye, C RESTAURA");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let world = saves.join("mksa-test");
    let region_dir = world.join("region");

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();

    const VICTIM: &str = "mcwstairs";
    const NS: &[u8] = b"mcwstairs";

    // ════════════ A: captura (corte 3) ════════════
    println!("[fase3-restore] A (modset completo): captura la huella");
    let (pid_a, mut s, mut reader) = open_session(&handle, &games, instancia);
    let mods = request(&mut s, &mut reader, 2, "state.mods");
    let all = mods["ok"]["mods"].as_array().cloned().unwrap_or_default();
    let victim_jars = validate_fixed_victim(&all, VICTIM);
    check(!victim_jars.is_empty(), &format!("A ve la víctima fija '{VICTIM}' con jar aislable"));

    let mut id = 3u64;
    println!("\n   >>> COLOCA varias escaleras de {VICTIM} en el mundo y quédate quieto dentro.");
    println!("   >>> (esperando wcg.counts{{{VICTIM}}}.blocks > 0 …)\n");
    let count_a = wait_counts_positive(&mut s, &mut reader, &mut id, VICTIM, 300);
    check(count_a > 0, &format!("A: huella colocada — wcg.counts{{{VICTIM}}}.blocks={count_a}"));

    let ep_a = request(&mut s, &mut reader, id, "state.epoch");
    id += 1;
    let epoch_a = ep_a["ok"]["epoch"].as_i64().unwrap_or(0);
    check(epoch_a != 0, &format!("A: epoch_A={epoch_a}"));

    let restore_path = world.join("mksa").join("restore").join(format!("tx-{:016x}.mksar", epoch_a as u64));
    let restore_path_str = restore_path.to_string_lossy().into_owned();
    let armed = request_p(&mut s, &mut reader, id, "ledger.sweepArm",
        serde_json::json!({ "ns": VICTIM, "path": restore_path_str }));
    id += 1;
    check(armed["ok"]["armed"].as_bool() == Some(true), "A: barrido armado");

    quiesce(&mut s, &mut reader, &mut id);
    drop(reader);
    drop(s);
    launch::close_game_gracefully(pid_a);
    check(wait_pid_dead(pid_a, 60), "A cerró limpio (captura escrita)");

    let cap = read_restore(&restore_path)
        .unwrap_or_else(|| { check(false, "el archivo de restauración existe"); unreachable!() });
    let cap_blocks = cap.blocks.len();
    check(cap_blocks > 0, &format!("A: capturó {cap_blocks} bloques de {VICTIM}"));

    // Muestra: los chunks con huella en disco (la que B destruirá y C debe restituir).
    let before = scan_region_chunks(&region_dir, NS);
    let world_sample: Vec<(i32, i32)> =
        before.iter().filter(|(_, (_, h))| *h).map(|(k, _)| *k).collect();
    check(!world_sample.is_empty(), &format!("ANTES de B: {} chunk(s) con huella", world_sample.len()));

    // ════════════ B: destrucción (corte 2 = A') ════════════
    println!("[fase3-restore] B (sin {VICTIM}): reescribe el mundo y destruye la huella");
    let result_b = launch::relaunch_disable(handle.clone(), games.clone(), instancia.into(), victim_jars.clone())
        .expect("relaunch_disable");
    check(!result_b.already_running, &format!("B lanzado, pid {}", result_b.pid));
    let (mut s2, mut reader2) = attach_session(&games, instancia);
    let mods_b = request(&mut s2, &mut reader2, 2, "state.mods");
    let all_b = mods_b["ok"]["mods"].as_array().cloned().unwrap_or_default();
    check(!all_b.iter().any(|m| m["id"].as_str() == Some(VICTIM)), &format!("B NO ve {VICTIM}"));

    let mut id2 = 4u64;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    loop {
        let (_, _, rt) = ledger_stats(&mut s2, &mut reader2, id2);
        id2 += 1;
        if rt > 0 { break; }
        if std::time::Instant::now() >= deadline { check(false, "B leyó chunks de A (readTotal>0)"); }
        std::thread::sleep(std::time::Duration::from_secs(5));
    }
    quiesce(&mut s2, &mut reader2, &mut id2);
    drop(reader2);
    drop(s2);
    launch::close_game_gracefully(result_b.pid);
    check(wait_pid_dead(result_b.pid, 60), "B cerró limpio");
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));
    check(region_chunks_intact(&region_dir), "disco: 0 corrupción tras B");

    let after_b = scan_region_chunks(&region_dir, NS);
    let world_lost = world_sample.iter().all(|k| !after_b.get(k).map(|(_, h)| *h).unwrap_or(false));
    check(world_lost, "B destruyó la huella en disco (A': los chunks de la muestra ya no llevan el ns)");

    // ════════════ C: restauración (re-inyección) ════════════
    // Devolver el jar de la víctima a mods/ → C arranca con el modset ORIGINAL, que
    // resuelve el namespace (no hay 'unknown registry key' como en B).
    restore_victim(instancia, &victim_jars);
    check(victim_jars.iter().all(|j| Path::new(j).is_file()), "jar de la víctima de vuelta en mods/ (C = modset original)");

    println!("[fase3-restore] C (modset original): re-inyecta la huella al cargar los chunks");
    let (pid_c, mut s3, mut reader3) = open_session(&handle, &games, instancia);
    // Armar la re-inyección lo ANTES posible: game-ready ocurre unos segundos antes de
    // que el servidor integrado cargue el mundo (carrera ganada en la práctica; los
    // chunks de la huella están en el spawn y se parsean tras armar).
    let mut id3 = 2u64;
    let r_armed = request_p(&mut s3, &mut reader3, id3, "ledger.restoreArm",
        serde_json::json!({ "path": restore_path_str }));
    id3 += 1;
    check(r_armed["ok"]["armed"].as_bool() == Some(true),
        &format!("C: re-inyección armada ({} bloques, {} chunks)", r_armed["ok"]["blocks"], r_armed["ok"]["chunks"]));

    // La re-inyección bumpea wcg.counts al parsear los chunks de la huella.
    let count_c = wait_counts_positive(&mut s3, &mut reader3, &mut id3, VICTIM, 180);
    check(count_c > 0, &format!("C: re-inyección VIVA — wcg.counts{{{VICTIM}}}.blocks={count_c} (>0 de nuevo)"));

    let stv = request(&mut s3, &mut reader3, id3, "ledger.stats");
    id3 += 1;
    let restored = stv["ok"]["restoredBlocks"].as_i64().unwrap_or(0);
    let conflicts = stv["ok"]["restoreConflicts"].as_i64().unwrap_or(0);
    let misses = stv["ok"]["restoreMisses"].as_i64().unwrap_or(0);
    println!("       ledger: restoredBlocks={restored} conflicts={conflicts} misses={misses}");
    check(restored > 0, &format!("C: el ledger re-inyectó {restored} bloque(s) en el NBT al leerlos"));

    quiesce(&mut s3, &mut reader3, &mut id3);
    drop(reader3);
    drop(s3);
    launch::close_game_gracefully(pid_c);
    check(wait_pid_dead(pid_c, 60), "C cerró limpio (re-guardó el mundo restaurado)");
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));
    check(region_chunks_intact(&region_dir), "disco: 0 corrupción tras C");

    // ════════════ Payoff: la huella reapareció en disco ════════════
    let after_c = scan_region_chunks(&region_dir, NS);
    let world_restored = world_sample.iter().any(|k| after_c.get(k).map(|(_, h)| *h).unwrap_or(false));
    check(world_restored, "DESPUÉS de C: la huella de bloques REAPARECIÓ en el .mca (re-guardada por C)");

    println!("\n   ═══ VEREDICTO CORTE 4 ═══");
    println!("   El ledger ESCRIBIÓ al mundo por primera vez: C re-inyectó {restored} bloque(s) de {VICTIM}");
    println!("   en el NBT al leer los chunks, con el modset original resolviendo el namespace.");
    println!("   B destruyó la huella (A'); C la restituyó: el BUCLE DE REVERSIBILIDAD se CIERRA para bloques.");
    println!("   Items (ItemStack opaco, components 1.20.5+) = corte 4b. Reconstrucción in-process real (§3.5) sigue siendo el end-goal.\n");

    check(victim_jars.iter().all(|j| Path::new(j).is_file()), "instancia intacta (víctima en mods/)");
}

/// Fase 3, corte 4b: cierre del bucle de reversibilidad para items del jugador.
/// Igual que el corte 4 de bloques, pero sobre `playerdata`: A captura un item en
/// la mochila, B lo destruye al reescribir sin el mod, y C lo reinyecta al leer el
/// NBT del jugador antes de que Minecraft lo decodifique.
/// Sondeo NO interactivo de la re-inyección de items (corte 4b). Reusa el restore
/// file y el playerdata que dejó la última corrida de --fase3-restore-items (el
/// playerdata ya NO tiene los items: B los borró, que es justo el estado de C).
/// Lanza SOLO la fase C: arma restoreArm, espera al login (lectura del playerdata)
/// y vuelca los contadores de diagnóstico nuevos (playerReadCalls/Inv/recsMatched/
/// playerReadDiag) para localizar dónde se cae la re-inyección — sin pedir nada al
/// usuario. No resetea el mundo ni toca mods (el modset ya es el original tras la
/// corrida previa). Para iterar rápido sobre el bug, no para verificar el corte.
fn fase3_restore_probe(instancia: &str) {
    println!("[fase3-restore-probe] sondeo C no interactivo: ¿por qué restoredItems=0?");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let world = saves.join("mksa-test");
    let restore_dir = world.join("mksa").join("restore");

    // El restore file más reciente que dejó la corrida interactiva.
    let restore_path = std::fs::read_dir(&restore_dir)
        .ok()
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|x| x.to_str()) == Some("mksar"))
        .filter(|p| p.file_name().and_then(|n| n.to_str()).map(|n| n.contains("items")).unwrap_or(false))
        .max_by_key(|p| std::fs::metadata(p).and_then(|m| m.modified()).ok());
    let restore_path = match restore_path {
        Some(p) => p,
        None => { check(false, &format!("hay un restore file de items en {}", restore_dir.display())); unreachable!() }
    };
    let cap = read_restore(&restore_path)
        .unwrap_or_else(|| { check(false, "el restore file parsea"); unreachable!() });
    println!("   restore file: {} ({} item(s), ns={})", restore_path.display(), cap.items.len(), cap.ns);
    let restore_path_str = restore_path.to_string_lossy().into_owned();

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();

    println!("[fase3-restore-probe] C (modset original): arma la re-inyeccion y lee el playerdata");
    let (pid_c, mut s3, mut reader3) = open_session(&handle, &games, instancia);
    let mut id3 = 2u64;
    let r_armed = request_p(&mut s3, &mut reader3, id3, "ledger.restoreArm",
        serde_json::json!({ "path": restore_path_str }));
    id3 += 1;
    check(
        r_armed["ok"]["armed"].as_bool() == Some(true),
        &format!("C: re-inyeccion armada ({} items, {} players)", r_armed["ok"]["items"], r_armed["ok"]["players"]),
    );

    // Espera a que el playerdata se haya leido (login) y vuelca diagnostico.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    loop {
        let v = request(&mut s3, &mut reader3, id3, "ledger.stats");
        id3 += 1;
        let ok = &v["ok"];
        let prc = ok["playerReadCalls"].as_i64().unwrap_or(0);
        let pri = ok["playerReadInv"].as_i64().unwrap_or(0);
        let recs = ok["itemRecsMatched"].as_i64().unwrap_or(0);
        let restored = ok["restoredItems"].as_i64().unwrap_or(0);
        let misses = ok["restoreMisses"].as_i64().unwrap_or(0);
        let diag = ok["playerReadDiag"].as_str().unwrap_or("?");
        println!("   playerReadCalls={prc} playerReadInv={pri} itemRecsMatched={recs} restoredItems={restored} misses={misses}");
        println!("   playerReadDiag = {diag}");
        if pri > 0 || restored > 0 || misses > 0 {
            println!("\n   >>> el playerdata YA paso por el hook de lectura. Diagnostico arriba.");
            break;
        }
        if std::time::Instant::now() >= deadline {
            println!("\n   >>> TIMEOUT: el hook de lectura NUNCA vio un CompoundTag con Inventory (playerReadInv=0).");
            println!("   >>> Si playerReadCalls=0 -> readCompressed no se engancho o no se llamo.");
            println!("   >>> Si playerReadCalls>0 pero Inv=0 -> el playerdata NO pasa por readCompressed (otra ruta).");
            break;
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    }

    drop(reader3);
    drop(s3);
    launch::close_game_gracefully(pid_c);
    wait_pid_dead(pid_c, 60);
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));
    println!("[fase3-restore-probe] fin del sondeo.");
}

fn fase3_restore_items_check(instancia: &str) {
    println!("[fase3-restore-items] corte 4b: re-inyeccion de items del jugador — A captura, B destruye, C RESTAURA");
    std::env::set_var("MKSA_QUICKPLAY_WORLD", "mksa-test");
    reset_test_world(instancia);

    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    ensure_no_pause_on_focus_loss(&inst.minecraft_dir);
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let world = saves.join("mksa-test");
    let template = saves.join("mksa-test-template");
    let template_names = playerdata_names(&template.join("playerdata"));

    let app = tauri::test::mock_app();
    let handle = app.handle().clone();
    let games = launch::Games::default();

    const VICTIM: &str = "mcwstairs";
    const NS: &[u8] = b"mcwstairs";

    println!("[fase3-restore-items] A (modset completo): captura el item en mochila");
    let (pid_a, mut s, mut reader) = open_session(&handle, &games, instancia);
    let mods = request(&mut s, &mut reader, 2, "state.mods");
    let all = mods["ok"]["mods"].as_array().cloned().unwrap_or_default();
    let victim_jars = validate_fixed_victim(&all, VICTIM);
    check(!victim_jars.is_empty(), &format!("A ve la victima fija '{VICTIM}' con jar aislable"));

    let ep_a = request(&mut s, &mut reader, 3, "state.epoch");
    let epoch_a = ep_a["ok"]["epoch"].as_i64().unwrap_or(0);
    check(epoch_a != 0, &format!("A: epoch_A={epoch_a}"));

    let wait_secs: u64 = std::env::var("MKSA_INV_WAIT_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(40);
    println!("\n   >>> COGE en tu mochila UN item de {VICTIM} y NO lo coloques.");
    println!("   >>> Quedate quieto dentro del mundo; el harness armara la captura y cerrara A.");
    println!("   >>> Tienes ~{wait_secs}s.\n");
    let start = std::time::Instant::now();
    while start.elapsed().as_secs() < wait_secs {
        std::thread::sleep(std::time::Duration::from_secs(1));
    }

    let restore_path = world.join("mksa").join("restore").join(format!("tx-items-{:016x}.mksar", epoch_a as u64));
    let restore_path_str = restore_path.to_string_lossy().into_owned();
    let mut id = 4u64;
    let armed = request_p(
        &mut s,
        &mut reader,
        id,
        "ledger.sweepArm",
        serde_json::json!({ "ns": VICTIM, "path": restore_path_str }),
    );
    id += 1;
    check(armed["ok"]["armed"].as_bool() == Some(true), "A: barrido armado");

    quiesce(&mut s, &mut reader, &mut id);
    drop(reader);
    drop(s);
    launch::close_game_gracefully(pid_a);
    check(wait_pid_dead(pid_a, 60), "A cerro limpio (captura escrita)");

    let cap = read_restore(&restore_path)
        .unwrap_or_else(|| { check(false, "el archivo de restauracion existe"); unreachable!() });
    let cap_items = cap.items.len();
    check(cap_items > 0, &format!("A: capturo {cap_items} item(s) de {VICTIM}"));
    check(
        cap.items.iter().all(|(_, nbt)| blob_has(nbt, NS)),
        "A: todo item capturado lleva el namespace de la victima",
    );

    let before = scan_playerdata(&world, NS);
    let mut sample: Vec<String> = before
        .iter()
        .filter(|(name, (has, _))| *has && !template_names.contains(*name))
        .map(|(n, _)| n.clone())
        .collect();
    sample.sort();
    check(!sample.is_empty(), &format!("ANTES de B: {} playerdata con huella", sample.len()));

    println!("[fase3-restore-items] B (sin {VICTIM}): reescribe el playerdata y destruye la huella");
    let result_b = launch::relaunch_disable(handle.clone(), games.clone(), instancia.into(), victim_jars.clone())
        .expect("relaunch_disable");
    check(!result_b.already_running, &format!("B lanzado, pid {}", result_b.pid));
    let (mut s2, mut reader2) = attach_session(&games, instancia);
    let mods_b = request(&mut s2, &mut reader2, 2, "state.mods");
    let all_b = mods_b["ok"]["mods"].as_array().cloned().unwrap_or_default();
    check(!all_b.iter().any(|m| m["id"].as_str() == Some(VICTIM)), &format!("B NO ve {VICTIM}"));

    let mut id2 = 4u64;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(150);
    loop {
        let (_, _, rt) = ledger_stats(&mut s2, &mut reader2, id2);
        id2 += 1;
        if rt > 0 { break; }
        if std::time::Instant::now() >= deadline { check(false, "B leyo chunks del mundo de A (readTotal>0)"); }
        std::thread::sleep(std::time::Duration::from_secs(5));
    }
    quiesce(&mut s2, &mut reader2, &mut id2);
    drop(reader2);
    drop(s2);
    launch::close_game_gracefully(result_b.pid);
    check(wait_pid_dead(result_b.pid, 60), "B cerro limpio");
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    let after_b = scan_playerdata(&world, NS);
    let lost_b = sample
        .iter()
        .all(|n| !after_b.get(n).map(|(h, _)| *h).unwrap_or(false));
    check(lost_b, "B destruyo la huella del item en playerdata (A')");

    restore_victim(instancia, &victim_jars);
    check(victim_jars.iter().all(|j| Path::new(j).is_file()), "jar de la victima de vuelta en mods/ (C = modset original)");

    println!("[fase3-restore-items] C (modset original): re-inyecta la huella al leer el playerdata");
    let (pid_c, mut s3, mut reader3) = open_session(&handle, &games, instancia);
    let mut id3 = 2u64;
    let r_armed = request_p(&mut s3, &mut reader3, id3, "ledger.restoreArm",
        serde_json::json!({ "path": restore_path_str }));
    id3 += 1;
    check(
        r_armed["ok"]["armed"].as_bool() == Some(true),
        &format!("C: re-inyeccion armada ({} items)", r_armed["ok"]["items"]),
    );

    let deadline_c = std::time::Instant::now() + std::time::Duration::from_secs(180);
    let restored_items = loop {
        let stv = request(&mut s3, &mut reader3, id3, "ledger.stats");
        id3 += 1;
        let restored = stv["ok"]["restoredItems"].as_i64().unwrap_or(0);
        let misses = stv["ok"]["restoreMisses"].as_i64().unwrap_or(0);
        let conflicts = stv["ok"]["restoreConflicts"].as_i64().unwrap_or(0);
        println!("       ledger: restoredItems={restored} conflicts={conflicts} misses={misses}");
        if restored > 0 {
            break restored;
        }
        if std::time::Instant::now() >= deadline_c {
            check(false, "C reinyecto items en vivo (restoredItems>0)");
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    };
    check(restored_items > 0, &format!("C: el ledger reinyecto {restored_items} item(s) al leer el playerdata"));

    quiesce(&mut s3, &mut reader3, &mut id3);
    drop(reader3);
    drop(s3);
    launch::close_game_gracefully(pid_c);
    check(wait_pid_dead(pid_c, 60), "C cerro limpio");
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("agent.json"));
    let _ = std::fs::remove_file(Path::new(instancia).join("mksa").join("launching.lock"));

    let after_c = scan_playerdata(&world, NS);
    let restored_c = sample
        .iter()
        .any(|n| after_c.get(n).map(|(h, _)| *h).unwrap_or(false));
    check(restored_c, "DESPUES de C: la huella del item REAPARECIO en playerdata");

    println!("\n   ═══ VEREDICTO CORTE 4b ═══");
    println!("   El ledger restituyo items del jugador por primera vez: C reinyecto {restored_items} item(s) de {VICTIM}");
    println!("   al leer el playerdata, con el modset original resolviendo el namespace.");
    println!("   B destruyo la huella (A'); C la restituyo: el bucle de reversibilidad se cierra para items del jugador.\n");

    check(victim_jars.iter().all(|j| Path::new(j).is_file()), "instancia intacta (victima en mods/)");
}

/// Contenido del archivo de restauración (corte 3), parseado del formato binario
/// propio que escribe `Ledger.RestoreWriter`. Ver su doc para el layout.
struct RestoreFile {
    ns: String,
    epoch: i64,
    blocks: Vec<(i32, i32, i32, Vec<u8>)>, // (x,y,z, NBT de la entrada de palette)
    items: Vec<(i32, Vec<u8>)>,            // (slot, NBT del ItemStack)
}

/// ¿El blob NBT contiene la secuencia de bytes del namespace? (byte-search, igual que
/// los lectores de .mca/playerdata: proxy fiable de "el dato de la víctima está aquí").
fn blob_has(blob: &[u8], ns: &[u8]) -> bool {
    !ns.is_empty() && blob.windows(ns.len()).any(|w| w == ns)
}

/// Parsea el archivo de restauración. None si no existe o el marco está corrupto.
fn read_restore(path: &Path) -> Option<RestoreFile> {
    fn i32at(b: &[u8], o: &mut usize) -> Option<i32> {
        if *o + 4 > b.len() { return None; }
        let v = i32::from_be_bytes([b[*o], b[*o + 1], b[*o + 2], b[*o + 3]]);
        *o += 4;
        Some(v)
    }
    fn i64at(b: &[u8], o: &mut usize) -> Option<i64> {
        if *o + 8 > b.len() { return None; }
        let mut a = [0u8; 8];
        a.copy_from_slice(&b[*o..*o + 8]);
        *o += 8;
        Some(i64::from_be_bytes(a))
    }
    fn blob<'a>(b: &'a [u8], o: &mut usize, n: usize) -> Option<&'a [u8]> {
        if *o + n > b.len() { return None; }
        let s = &b[*o..*o + n];
        *o += n;
        Some(s)
    }

    let b = std::fs::read(path).ok()?;
    if b.len() < 7 || &b[..7] != b"MKSAR1\n" {
        return None;
    }
    let mut o = 7usize;
    let nslen = i32at(&b, &mut o)? as usize;
    let ns = String::from_utf8_lossy(blob(&b, &mut o, nslen)?).into_owned();
    let epoch = i64at(&b, &mut o)?;
    let mut blocks = Vec::new();
    let mut items = Vec::new();
    while o < b.len() {
        let kind = b[o];
        o += 1;
        match kind {
            b'B' => {
                let x = i32at(&b, &mut o)?;
                let y = i32at(&b, &mut o)?;
                let z = i32at(&b, &mut o)?;
                let nl = i32at(&b, &mut o)? as usize;
                let nbt = blob(&b, &mut o, nl)?.to_vec();
                let bl = i32at(&b, &mut o)?;
                if bl >= 0 {
                    blob(&b, &mut o, bl as usize)?; // be_nbt: se salta (no se audita en el corte)
                }
                blocks.push((x, y, z, nbt));
            }
            b'I' => {
                let ul = i32at(&b, &mut o)? as usize;
                blob(&b, &mut o, ul)?; // uuid: se salta
                let slot = i32at(&b, &mut o)?;
                let nl = i32at(&b, &mut o)? as usize;
                let nbt = blob(&b, &mut o, nl)?.to_vec();
                items.push((slot, nbt));
            }
            _ => return None, // marco corrupto
        }
    }
    Some(RestoreFile { ns, epoch, blocks, items })
}

fn playerdata_names(dir: &Path) -> std::collections::HashSet<String> {
    let mut out = std::collections::HashSet::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for e in entries.flatten() {
            let p = e.path();
            if p.extension().and_then(|x| x.to_str()) == Some("dat") {
                if let Some(name) = p.file_name() {
                    out.insert(name.to_string_lossy().into_owned());
                }
            }
        }
    }
    out
}

/// Snapshot del `playerdata` de un mundo: `nombre_archivo → (¿contiene el namespace?,
/// hash del NBT descomprimido)`. Cada `.dat` es un gzip NBT **entero** (no el
/// contenedor de regiones), de ahí `GzDecoder` sobre todo el archivo. El hash
/// (DefaultHasher sobre el NBT descomprimido, no sobre los bytes gzip — el header gzip
/// lleva mtime y variaría sin que cambie el contenido) es el detector de REESCRITURA:
/// no hay sello de época en el playerdata, así que comparar hash ANTES/DESPUÉS es lo
/// que distingue "B reescribió" de "B no tocó el archivo".
fn scan_playerdata(world: &Path, ns: &[u8]) -> std::collections::HashMap<String, (bool, u64)> {
    use std::hash::Hasher as _;
    let mut out = std::collections::HashMap::new();
    let dir = world.join("playerdata");
    let entries = match std::fs::read_dir(&dir) {
        Ok(e) => e,
        Err(_) => return out,
    };
    for e in entries.flatten() {
        let p = e.path();
        if p.extension().and_then(|x| x.to_str()) != Some("dat") {
            continue; // ignora .dat_old (backups)
        }
        let raw = match std::fs::read(&p) {
            Ok(b) => b,
            Err(_) => continue,
        };
        let mut nbt = Vec::new();
        if flate2::read::GzDecoder::new(&raw[..]).read_to_end(&mut nbt).is_err() {
            continue;
        }
        let has_ns = nbt.windows(ns.len()).any(|w| w == ns);
        let mut h = std::collections::hash_map::DefaultHasher::new();
        h.write(&nbt);
        let hash = h.finish();
        if let Some(name) = p.file_name() {
            out.insert(name.to_string_lossy().into_owned(), (has_ns, hash));
        }
    }
    out
}


/// y sin re-provisión jar-in-jar por otro mod habilitado — pero SIN el filtro de WCG
/// (el corte 2 quiere huella, no ausencia). Devuelve sus jars; aborta si no cumple.
fn validate_fixed_victim(all: &[serde_json::Value], victim: &str) -> Vec<String> {
    let mut depended: std::collections::HashSet<String> = std::collections::HashSet::new();
    for m in all {
        if let Some(ds) = m["deps"].as_array() {
            for d in ds {
                if let Some(s) = d.as_str() {
                    depended.insert(s.to_string());
                }
            }
        }
    }
    let m = all
        .iter()
        .find(|m| m["id"].as_str() == Some(victim))
        .unwrap_or_else(|| {
            check(false, &format!("la instancia contiene la víctima fija '{victim}'"));
            unreachable!()
        });
    check(!depended.contains(victim), &format!("{victim} es hoja (ningún mod lo declara dependencia)"));
    let files: Vec<String> = m["files"]
        .as_array()
        .map(|a| a.iter().filter_map(|f| f.as_str().map(String::from)).collect())
        .unwrap_or_default();
    check(!files.is_empty(), &format!("{victim} tiene jar aislable en disco"));
    let nested = nested_providers(all);
    if let Some(providers) = nested.get(victim) {
        let removed: std::collections::HashSet<&str> = files.iter().map(|s| s.as_str()).collect();
        let leftover = providers.iter().filter(|p| !removed.contains(p.as_str())).count();
        check(leftover == 0, &format!("{victim} no queda re-provisto nested por otro mod habilitado"));
    }
    files
}

/// Poll de `wcg.counts{ns}.blocks` hasta que sea > 0 (o timeout). Devuelve el conteo.
/// Lo usa el corte 2 para confirmar —de forma observacional— que el usuario ya colocó
/// huella del mod víctima antes de cerrar A.
/// Espera a que el contador `setBlocks` del ledger crezca al menos `delta` sobre la
/// base dada (mutaciones in-vivo del usuario). Robusto al chunk fantasma de la plantilla
/// (que solo aparece en wcg.counts, no en setBlocks que requiere setBlockState in-vivo).
/// Espera a que `setBlocks` se mantenga sin crecer durante `stable_secs` segundos
/// seguidos — heurística de "el mundo terminó de cargar / no hay churn de worldgen".
/// Devuelve el valor estable de setBlocks (= nuevo baseline post-load). Cota dura por
/// `timeout_secs`. Útil para distinguir "actividad del usuario" de "churn de carga"
/// cuando el harness depende de detectar mutaciones específicas.
#[allow(dead_code)]
fn wait_setblocks_quiescent(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: &mut u64,
    stable_secs: u64,
    timeout_secs: u64,
) -> i64 {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
    let mut last_value = -1i64;
    let mut last_change = std::time::Instant::now();
    loop {
        let v = request(s, reader, *id, "ledger.stats");
        *id += 1;
        let cur = v["ok"]["setBlocks"].as_i64().unwrap_or(0);
        if cur != last_value {
            last_value = cur;
            last_change = std::time::Instant::now();
        } else if last_change.elapsed() >= std::time::Duration::from_secs(stable_secs) {
            return cur;
        }
        if std::time::Instant::now() >= deadline {
            return cur;
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}

fn wait_set_blocks_delta(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: &mut u64,
    base: i64,
    delta: i64,
    timeout_secs: u64,
) -> i64 {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
    let mut ticks = 0u32;
    loop {
        let v = request(s, reader, *id, "ledger.stats");
        *id += 1;
        let cur = v["ok"]["setBlocks"].as_i64().unwrap_or(0);
        let d = cur - base;
        if d >= delta {
            return d;
        }
        if std::time::Instant::now() >= deadline {
            return d;
        }
        ticks += 1;
        if ticks % 5 == 0 {
            println!("       … sigo esperando mutaciones del usuario (setBlocks Δ={d}/{delta})");
        }
        std::thread::sleep(std::time::Duration::from_secs(3));
    }
}

#[allow(dead_code)]
fn wait_counts_positive(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: &mut u64,
    ns: &str,
    timeout_secs: u64,
) -> i64 {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
    let mut ticks = 0u32;
    loop {
        let v = request_p(s, reader, *id, "wcg.counts", serde_json::json!({ "ns": ns }));
        *id += 1;
        let blocks = v["ok"]["blocks"].as_i64().unwrap_or(0);
        if blocks > 0 {
            return blocks;
        }
        if std::time::Instant::now() >= deadline {
            return 0;
        }
        ticks += 1;
        if ticks % 5 == 0 {
            println!("       … sigo esperando huella de {ns} (wcg.counts.blocks=0)");
        }
        std::thread::sleep(std::time::Duration::from_secs(3));
    }
}

/// Espera a que un pid muera (cierre con gracia). Devuelve true si murió a tiempo.
fn wait_pid_dead(pid: u32, secs: u64) -> bool {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(secs);
    while std::time::Instant::now() < deadline {
        if !launch::pid_alive(pid) {
            return true;
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
    !launch::pid_alive(pid)
}

/// Observación per-chunk del `.mca`: para cada chunk presente devuelve
/// `(cx,cz) → (epoch?, ¿contiene el namespace?)`. `epoch` se lee de `mksa:prov.epoch`
/// (el long sellado por el write-hook = `state.epoch.epoch`); None si el chunk no
/// lleva el sello (proto-chunk, o fallback `{epoch:0}` que se lee como Some(0)).
/// La presencia del namespace es un byte-search en el NBT descomprimido (palette de
/// blockstates) — proxy de "persistió la huella", con el caveat documentado de que
/// no equivale a "restaurable".
fn scan_region_chunks(
    region_dir: &Path,
    ns: &[u8],
) -> std::collections::HashMap<(i32, i32), (Option<i64>, bool)> {
    let mut out: std::collections::HashMap<(i32, i32), (Option<i64>, bool)> =
        std::collections::HashMap::new();
    let entries = match std::fs::read_dir(region_dir) {
        Ok(e) => e,
        Err(_) => return out,
    };
    for e in entries.flatten() {
        let p = e.path();
        if p.extension().and_then(|x| x.to_str()) != Some("mca") {
            continue;
        }
        let bytes = match std::fs::read(&p) {
            Ok(b) => b,
            Err(_) => continue,
        };
        if bytes.len() < 8192 {
            continue;
        }
        for i in 0..1024usize {
            let h = i * 4;
            let loc = u32::from_be_bytes([bytes[h], bytes[h + 1], bytes[h + 2], bytes[h + 3]]);
            let offset = (loc >> 8) as usize;
            let count = (loc & 0xff) as usize;
            if offset < 2 || count == 0 {
                continue;
            }
            let start = offset * 4096;
            if start + 5 > bytes.len() {
                continue;
            }
            let len = u32::from_be_bytes([
                bytes[start],
                bytes[start + 1],
                bytes[start + 2],
                bytes[start + 3],
            ]) as usize;
            if len == 0 {
                continue;
            }
            let ctype = bytes[start + 4];
            if ctype & 0x80 != 0 {
                continue; // chunk externo (.mcc)
            }
            let data_start = start + 5;
            let data_end = start + 4 + len;
            if data_end > bytes.len() {
                continue;
            }
            let comp = &bytes[data_start..data_end];
            let mut nbt = Vec::new();
            let ok = match ctype {
                1 => flate2::read::GzDecoder::new(comp).read_to_end(&mut nbt).is_ok(),
                2 => flate2::read::ZlibDecoder::new(comp).read_to_end(&mut nbt).is_ok(),
                3 => {
                    nbt.extend_from_slice(comp);
                    true
                }
                _ => false,
            };
            if !ok {
                continue;
            }
            let cx = match nbt_int(&nbt, b"xPos") {
                Some(v) => v,
                None => continue, // sin coords no se puede ubicar (corrupción: la ve region_chunks_intact)
            };
            let cz = match nbt_int(&nbt, b"zPos") {
                Some(v) => v,
                None => continue,
            };
            let epoch = nbt_long(&nbt, b"epoch");
            let has_ns = nbt.windows(ns.len()).any(|w| w == ns);
            out.insert((cx, cz), (epoch, has_ns));
        }
    }
    out
}

/// Lee un `TAG_Int` (0x03) de nombre `name` del NBT crudo: busca el patrón
/// `0x03 | len(name) u16 BE | name` y devuelve los 4 bytes BE siguientes.
fn nbt_int(nbt: &[u8], name: &[u8]) -> Option<i32> {
    let pat = nbt_pattern(0x03, name);
    let pos = find_sub(nbt, &pat)?;
    let v = pos + pat.len();
    if v + 4 > nbt.len() {
        return None;
    }
    Some(i32::from_be_bytes([nbt[v], nbt[v + 1], nbt[v + 2], nbt[v + 3]]))
}

/// Lee un `TAG_Long` (0x04) de nombre `name`: patrón + 8 bytes BE.
fn nbt_long(nbt: &[u8], name: &[u8]) -> Option<i64> {
    let pat = nbt_pattern(0x04, name);
    let pos = find_sub(nbt, &pat)?;
    let v = pos + pat.len();
    if v + 8 > nbt.len() {
        return None;
    }
    Some(i64::from_be_bytes([
        nbt[v], nbt[v + 1], nbt[v + 2], nbt[v + 3], nbt[v + 4], nbt[v + 5], nbt[v + 6], nbt[v + 7],
    ]))
}

/// Patrón NBT de un tag con nombre: `tag_id | nombre.len() u16 BE | nombre`.
fn nbt_pattern(tag_id: u8, name: &[u8]) -> Vec<u8> {
    let mut p = Vec::with_capacity(3 + name.len());
    p.push(tag_id);
    p.push((name.len() >> 8) as u8);
    p.push((name.len() & 0xff) as u8);
    p.extend_from_slice(name);
    p
}

/// Primera posición de `needle` en `hay` (byte-search).
fn find_sub(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return None;
    }
    hay.windows(needle.len()).position(|w| w == needle)
}

/// Elige la víctima del relaunch combinando **capacidad estática** (tier) y
/// **evidencia observada** (WCG), que no compiten: el tier dice qué *puede* dejar
/// un mod en el mundo; el WCG dice qué *dejó* en este mundo concreto. El requisito
/// real del corte no es "el mod es T0" sino "quitarlo no rompe la deserialización
/// de este mundo en B"; el tier era solo un proxy estático de eso.
///
/// En todos los casos la víctima debe ser HOJA (si fuera dependencia el loader se
/// negaría a arrancar B) y tener jar(es) aislables que no comparta con otro mod
/// (un jar multi-mod desactivaría de más).
///
/// Prioridad:
///   1. T0 hoja — garantía de capacidad: sin `.class`, el mod no tiene código para
///      colocar bloques ni spawnear entidades; solo datos (recetas/loot/worldgen).
///   2. Si no hay T0 hoja: cualquier hoja con `wcg.counts == 0` en **todos** sus
///      namespaces — garantía dinámica: este mod no dejó presencia en este mundo.
///      Entre los candidatos, el de **tier más bajo** (menos capacidad ⇒ menos
///      riesgo en los huecos que el agregado aún no mide: entidades, items en
///      contenedores, runtime).
///
/// Límite documentado del paso 2: `wcg.counts` hoy mide **bloques** en los chunks
/// **cargados** esta sesión (entidades/containerItems aún en 0). Tras `quiesce` el
/// spawn ya se serializó, así que en un mundo de prueba chico la cobertura es
/// efectivamente total; como garantía general, `counts==0` es necesario pero no
/// suficiente, y por eso el desempate prefiere el tier más bajo. La razón de la
/// elección se registra explícitamente.
fn pick_victim(
    all: &[serde_json::Value],
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: &mut u64,
) -> (String, Vec<String>) {
    let mut depended: std::collections::HashSet<String> = std::collections::HashSet::new();
    for m in all {
        if let Some(ds) = m["deps"].as_array() {
            for d in ds {
                if let Some(s) = d.as_str() {
                    depended.insert(s.to_string());
                }
            }
        }
    }

    // Mapa id-anidado → jares contenedor que lo embarcan (jar-in-jar). Una víctima
    // solo es válida si TODO contenedor que provee su id está entre los jares que
    // movemos: si otro mod habilitado lo embarca, B carga la copia anidada y la
    // transición no ocurre (caso real: placeholder-api embarcado dentro de modmenu).
    let nested = nested_providers(all);

    // Jar(es) aislables de un mod: con archivos en disco, hoja, sin compartir jar
    // con otro mod, y sin que su id quede re-provisto nested por un jar que NO
    // movemos.
    let isolable = |m: &serde_json::Value| -> Option<(String, Vec<String>)> {
        let id = m["id"].as_str().unwrap_or("");
        if id.is_empty() || depended.contains(id) {
            return None;
        }
        let files: Vec<String> = m["files"]
            .as_array()
            .map(|a| a.iter().filter_map(|f| f.as_str().map(String::from)).collect())
            .unwrap_or_default();
        if files.is_empty() {
            return None;
        }
        let shared = files.iter().any(|f| {
            all.iter().any(|o| {
                o["id"].as_str() != Some(id)
                    && o["files"]
                        .as_array()
                        .is_some_and(|a| a.iter().any(|x| x.as_str() == Some(f.as_str())))
            })
        });
        if shared {
            return None;
        }
        // ¿Algún contenedor que provee `id` nested NO está entre los jares a mover?
        if let Some(providers) = nested.get(id) {
            let removed: std::collections::HashSet<&str> = files.iter().map(|s| s.as_str()).collect();
            let leftover: Vec<&String> =
                providers.iter().filter(|p| !removed.contains(p.as_str())).collect();
            if !leftover.is_empty() {
                println!(
                    "       descartado {id}: re-provisto nested por {} jar(es) que no se mueven (jar-in-jar)",
                    leftover.len()
                );
                return None;
            }
        }
        Some((id.to_string(), files))
    };

    // ── Prioridad 1: T0 hoja aislable (garantía de capacidad) ──
    for m in all {
        if m["tier"].as_i64() != Some(0) {
            continue;
        }
        if let Some((vid, files)) = isolable(m) {
            println!("       víctima={vid} reason=t0_leaf tier=0 leaf=true");
            return (vid, files);
        }
    }

    // ── Prioridad 2: hoja aislable con wcg.counts==0, preferir tier más bajo ──
    println!("       no hay T0 hoja aislable → fallback por evidencia del WCG (wcg.counts==0)");
    let mut best: Option<(i64, String, Vec<String>, i64)> = None; // (tier, id, files, blocks)
    for m in all {
        let (vid, files) = match isolable(m) {
            Some(v) => v,
            None => continue,
        };
        let tier = m["tier"].as_i64().unwrap_or(i64::MAX);
        // Sumar bloques observados en TODOS los namespaces del mod.
        let namespaces: Vec<String> = m["namespaces"]
            .as_array()
            .map(|a| a.iter().filter_map(|n| n.as_str().map(String::from)).collect())
            .unwrap_or_else(|| vec![vid.clone()]);
        let mut total_blocks = 0i64;
        for ns in &namespaces {
            let v = request_p(s, reader, *id, "wcg.counts", serde_json::json!({ "ns": ns }));
            *id += 1;
            total_blocks += v["ok"]["blocks"].as_i64().unwrap_or(0);
        }
        println!(
            "       candidato {vid}: tier={tier} ns={namespaces:?} blocks={total_blocks}"
        );
        if total_blocks != 0 {
            continue;
        }
        // Empate: tier más bajo gana (menos capacidad ⇒ menos riesgo no medido).
        let better = match &best {
            None => true,
            Some((bt, _, _, _)) => tier < *bt,
        };
        if better {
            best = Some((tier, vid, files, total_blocks));
        }
    }
    if let Some((tier, vid, files, blocks)) = best {
        println!(
            "       víctima={vid} reason=no_t0_leaf_found tier={tier} wcg.counts.blocks={blocks} leaf=true"
        );
        println!(
            "       note=wcg.counts cubre bloques en chunks cargados (entidades/items aún no medidos); desempate por tier más bajo"
        );
        return (vid, files);
    }
    check(false, "hay una víctima válida: T0 hoja, o hoja aislable con wcg.counts==0");
    unreachable!()
}

/// Mapa `id-de-mod-anidado → [jares contenedor]` escaneando el jar-in-jar (JiJ) de
/// cada mod. El loader expone en runtime solo el ganador de cada id (una copia
/// standalone tapa a la anidada), así que la copia anidada *dormida* es invisible
/// a `state.mods`; el único signo fiable es el contenido físico de los jares.
///
/// Fabric/Quilt embarcan jares anidados en `META-INF/jars/*.jar`, cada uno con su
/// `fabric.mod.json` (o `quilt.mod.json`) que declara su `id`. Forge/NeoForge usan
/// `META-INF/jarjar/` + `mods.toml`; no se cubre aquí (la instancia de prueba es
/// Fabric) — un id no encontrado simplemente no aparece en el mapa, y el filtro de
/// `isolable` lo trata como "sin re-provisión nested" (comportamiento previo).
fn nested_providers(all: &[serde_json::Value]) -> std::collections::HashMap<String, Vec<String>> {
    let mut map: std::collections::HashMap<String, Vec<String>> = std::collections::HashMap::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for m in all {
        if let Some(files) = m["files"].as_array() {
            for f in files.iter().filter_map(|f| f.as_str()) {
                if !seen.insert(f.to_string()) {
                    continue; // jar ya escaneado
                }
                for nid in nested_ids_in_jar(Path::new(f)) {
                    map.entry(nid).or_default().push(f.to_string());
                }
            }
        }
    }
    map
}

/// Ids de mod embarcados nested dentro de `jar` (un nivel: las entradas
/// `META-INF/jars/*.jar`, leyendo el `fabric.mod.json`/`quilt.mod.json` de cada
/// una). Best-effort: cualquier error de lectura devuelve lo hallado hasta ahí.
fn nested_ids_in_jar(jar: &Path) -> Vec<String> {
    let mut ids = Vec::new();
    let file = match std::fs::File::open(jar) {
        Ok(f) => f,
        Err(_) => return ids,
    };
    let mut zip = match zip::ZipArchive::new(file) {
        Ok(z) => z,
        Err(_) => return ids,
    };
    let names: Vec<String> = (0..zip.len())
        .filter_map(|i| zip.by_index(i).ok().map(|e| e.name().to_string()))
        .filter(|n| n.starts_with("META-INF/jars/") && n.ends_with(".jar"))
        .collect();
    for name in names {
        let mut buf = Vec::new();
        if let Ok(mut entry) = zip.by_name(&name) {
            if entry.read_to_end(&mut buf).is_err() {
                continue;
            }
        } else {
            continue;
        }
        // Abrir el jar anidado desde memoria y leer su fabric.mod.json.
        let cursor = std::io::Cursor::new(buf);
        if let Ok(mut inner) = zip::ZipArchive::new(cursor) {
            for meta in ["fabric.mod.json", "quilt.mod.json"] {
                if let Ok(mut e) = inner.by_name(meta) {
                    let mut txt = String::new();
                    if e.read_to_string(&mut txt).is_ok() {
                        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&txt) {
                            // Fabric: top-level "id". Quilt: quilt_loader.id.
                            let id = v["id"]
                                .as_str()
                                .or_else(|| v["quilt_loader"]["id"].as_str());
                            if let Some(id) = id {
                                ids.push(id.to_string());
                            }
                        }
                    }
                    break;
                }
            }
        }
    }
    ids
}

/// Devuelve a mods/ los jar movidos por el relaunch (desde `<instancia>/mksa/disabled/`).
fn restore_victim(instancia: &str, victim_jars: &[String]) {
    let disabled = Path::new(instancia).join("mksa").join("disabled");
    for orig in victim_jars {
        let orig_path = Path::new(orig);
        if let Some(name) = orig_path.file_name() {
            let staged = disabled.join(name);
            if staged.is_file() {
                let _ = std::fs::rename(&staged, orig_path);
            }
        }
    }
}

/// Auditoría estructural barata de los .mca: todo chunk presente debe conservar la
/// clave NBT `xPos` (una de las que la corrupción de P3 borraba — y presente en
/// CUALQUIER chunk guardado, full o proto, a diferencia de `sections`). No es un
/// parser NBT completo —busca la clave en el payload descomprimido, como
/// region_has_prov— pero detecta exactamente el síntoma de aquella corrupción.
fn region_chunks_intact(region_dir: &Path) -> bool {
    let mut ok = true;
    if let Ok(entries) = std::fs::read_dir(region_dir) {
        for e in entries.flatten() {
            let p = e.path();
            if p.extension().and_then(|x| x.to_str()) != Some("mca") {
                continue;
            }
            match region_all_chunks_have(&p, b"xPos") {
                Ok(true) => {}
                Ok(false) => {
                    println!(
                        "       ✘ {} tiene un chunk sin 'xPos'",
                        p.file_name().unwrap().to_string_lossy()
                    );
                    ok = false;
                }
                Err(err) => println!("       (no se pudo leer {}: {err})", p.display()),
            }
        }
    }
    ok
}

/// ¿TODO chunk presente de este region file contiene `key` en su NBT? (Mismo
/// recorrido del .mca que region_has_prov, pero exige la clave en cada chunk en
/// vez de en alguno.) Devuelve false en cuanto un chunk presente no la tiene.
fn region_all_chunks_have(path: &Path, key: &[u8]) -> std::io::Result<bool> {
    let bytes = std::fs::read(path)?;
    if bytes.len() < 8192 {
        return Ok(true); // header-only / región vacía
    }
    for i in 0..1024usize {
        let h = i * 4;
        let e = u32::from_be_bytes([bytes[h], bytes[h + 1], bytes[h + 2], bytes[h + 3]]);
        let offset = (e >> 8) as usize;
        let count = (e & 0xff) as usize;
        if offset < 2 || count == 0 {
            continue;
        }
        let start = offset * 4096;
        if start + 5 > bytes.len() {
            continue;
        }
        let len =
            u32::from_be_bytes([bytes[start], bytes[start + 1], bytes[start + 2], bytes[start + 3]])
                as usize;
        if len == 0 {
            continue;
        }
        let ctype = bytes[start + 4];
        if ctype & 0x80 != 0 {
            continue; // chunk externo (.mcc)
        }
        let data_start = start + 5;
        let data_end = start + 4 + len;
        if data_end > bytes.len() {
            continue;
        }
        let comp = &bytes[data_start..data_end];
        let mut out = Vec::new();
        let ok = match ctype {
            1 => flate2::read::GzDecoder::new(comp).read_to_end(&mut out).is_ok(),
            2 => flate2::read::ZlibDecoder::new(comp).read_to_end(&mut out).is_ok(),
            3 => {
                out.extend_from_slice(comp);
                true
            }
            _ => false,
        };
        if !ok {
            continue; // ilegible: no lo contamos como corrupción de 'sections'
        }
        if !out.windows(key.len()).any(|w| w == key) {
            return Ok(false);
        }
    }
    Ok(true)
}

/// Lanza la instancia (quickplay vía MKSA_QUICKPLAY_WORLD ya puesto en el
/// entorno), espera el archivo de contacto, hace el handshake con el token real
/// y consume las etapas hasta game-ready. Devuelve (pid, socket, reader).
/// El handshake del verificador desplaza la conexión del supervisor (§1.4), igual
/// que en launch_check.
fn open_session<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    games: &launch::Games,
    instancia: &str,
) -> (u32, std::net::TcpStream, BufReader<std::net::TcpStream>) {
    let result = launch::launch(app.clone(), games.clone(), instancia.into()).expect("lanzamiento");
    check(!result.already_running, &format!("juego lanzado, pid {}", result.pid));
    let (s, reader) = attach_session(games, instancia);
    (result.pid, s, reader)
}

/// Espera el contacto de un juego YA lanzado, hace el handshake con su token (del
/// mapa `games`) y consume las etapas hasta game-ready. Lo usan tanto open_session
/// (juego recién lanzado) como el corte de Fase 3 para enganchar al proceso B tras
/// `relaunch_disable`, que lo lanza por su cuenta.
///
/// Reintenta el enganche: el supervisor del launcher (`ipc::supervise`) abre su
/// PROPIA conexión y compite por el único socket del agente (§1.4, "el más reciente
/// gana"). Conecta una sola vez; en cuanto reconectamos DESPUÉS que él, ganamos de
/// forma permanente. Sin el reintento el enganche es una carrera (flaky): un
/// desplazamiento justo tras el handshake mata la conexión del verificador.
fn attach_session(
    games: &launch::Games,
    instancia: &str,
) -> (std::net::TcpStream, BufReader<std::net::TcpStream>) {
    let contact = Path::new(instancia).join("mksa").join("agent.json");
    let mut waited = 0u32;
    while !contact.is_file() {
        if waited >= 120_000 {
            check(false, "agent.json apareció antes de 120s");
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
        waited += 500;
    }
    let v: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&contact).unwrap()).unwrap();
    let port = v["port"].as_u64().unwrap() as u16;
    check(true, &format!("contacto publicado tras ~{waited}ms (pid={} port={port})", v["pid"]));

    let token = games
        .lock()
        .unwrap()
        .get(instancia)
        .map(|g| g.token.clone())
        .expect("token del juego en el mapa");

    let mut last = String::new();
    for _ in 0..10 {
        match try_attach_once(port, &token) {
            Ok(pair) => return pair,
            Err(e) => {
                last = e;
                std::thread::sleep(std::time::Duration::from_millis(600));
            }
        }
    }
    check(false, &format!("enganche estable al agente (último intento: {last})"));
    unreachable!()
}

/// Un intento de enganche: conecta, hace el hello y consume las etapas hasta
/// game-ready. Devuelve Err (para reintentar) si el agente cierra la conexión
/// —EOF, 0 bytes— porque el supervisor del launcher nos desplazó; los demás
/// errores de protocolo cortan el test de inmediato.
fn try_attach_once(
    port: u16,
    token: &str,
) -> Result<(std::net::TcpStream, BufReader<std::net::TcpStream>), String> {
    let mut s = std::net::TcpStream::connect(("127.0.0.1", port)).map_err(|e| e.to_string())?;
    let hello = serde_json::json!({
        "t":"req","id":1,"m":"hello",
        "p":{"proto":1,"side":"launcher","version":"verify","token":token}
    });
    s.write_all(format!("{hello}\n").as_bytes()).map_err(|e| e.to_string())?;
    let mut reader = BufReader::new(s.try_clone().map_err(|e| e.to_string())?);
    let mut line = String::new();
    if reader.read_line(&mut line).map_err(|e| e.to_string())? == 0 {
        return Err("EOF durante el hello".into());
    }
    let res: serde_json::Value = serde_json::from_str(&line).map_err(|e| e.to_string())?;
    if !res["ok"].is_object() {
        check(false, &format!("hello rechazado: {}", res["err"]["code"]));
    }
    // El arranque hasta game-ready (init del cliente + carga del mundo) puede tardar.
    s.set_read_timeout(Some(std::time::Duration::from_secs(300))).map_err(|e| e.to_string())?;
    let mut stage = res["ok"]["boot"].as_str().unwrap_or("premain").to_string();
    while stage != "game-ready" {
        let mut l = String::new();
        if reader.read_line(&mut l).map_err(|e| e.to_string())? == 0 {
            return Err("desplazado por el supervisor (EOF antes de game-ready)".into());
        }
        let v: serde_json::Value = match serde_json::from_str(&l) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if v["t"] == "evt" && v["m"] == "boot.stage" {
            stage = v["p"]["stage"].as_str().unwrap_or("?").to_string();
            println!("       boot.stage {stage} {}", v["p"]["detail"]);
        } else if v["t"] == "evt" && v["m"] == "boot.failed" {
            check(false, &format!("boot.failed: {}", v["p"]));
        }
    }
    check(
        true,
        &format!("handshake aceptado + game-ready (sesión {})", res["ok"]["session"]),
    );
    Ok((s, reader))
}

/// ledger.stats → (written, readWithProv, readTotal).
fn ledger_stats(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: u64,
) -> (i64, i64, i64) {
    let v = request(s, reader, id, "ledger.stats");
    if !v["ok"].is_object() {
        check(false, &format!("ledger.stats respondió ok (err={})", v["err"]));
    }
    (
        v["ok"]["written"].as_i64().unwrap_or(0),
        v["ok"]["readWithProv"].as_i64().unwrap_or(0),
        v["ok"]["readTotal"].as_i64().unwrap_or(0),
    )
}

/// El servidor integrado se pausa si la ventana pierde el foco en singleplayer;
/// sin ticks no hay autosave. Forzamos pauseOnLostFocus:false en options.txt.
fn ensure_no_pause_on_focus_loss(minecraft_dir: &str) {
    let opts = Path::new(minecraft_dir).join("options.txt");
    let mut lines: Vec<String> = std::fs::read_to_string(&opts)
        .map(|t| t.lines().map(|l| l.to_string()).collect())
        .unwrap_or_default();
    let mut found = false;
    for l in lines.iter_mut() {
        if l.starts_with("pauseOnLostFocus:") {
            *l = "pauseOnLostFocus:false".into();
            found = true;
        }
    }
    if !found {
        lines.push("pauseOnLostFocus:false".into());
    }
    let _ = std::fs::write(&opts, lines.join("\n") + "\n");
    println!("       options.txt: pauseOnLostFocus:false (el servidor integrado no se pausa sin foco)");
}

/// Recorre todos los region/*.mca y reporta si alguno tiene un chunk con mksa:prov.
fn scan_region_for_prov(region_dir: &Path) -> bool {
    let mut found = false;
    if let Ok(entries) = std::fs::read_dir(region_dir) {
        for e in entries.flatten() {
            let p = e.path();
            if p.extension().and_then(|x| x.to_str()) != Some("mca") {
                continue;
            }
            match region_has_prov(&p) {
                Ok(true) => {
                    println!("       ✔ {} contiene mksa:prov", p.file_name().unwrap().to_string_lossy());
                    found = true;
                }
                Ok(false) => {}
                Err(err) => println!("       (no se pudo leer {}: {err})", p.display()),
            }
        }
    }
    found
}

/// ¿Algún chunk de este region file contiene la clave NBT `mksa:prov`?
/// Formato del .mca: header de 4 KiB (1024 entradas de localización, 3 bytes de
/// offset en sectores + 1 byte de cuenta), 4 KiB de timestamps, luego los chunks
/// (u32 BE de longitud + byte de compresión + payload). La clave vive como UTF-8
/// dentro del NBT comprimido, así que hay que descomprimir y buscar los bytes.
fn region_has_prov(path: &Path) -> std::io::Result<bool> {
    let bytes = std::fs::read(path)?;
    if bytes.len() < 8192 {
        return Ok(false);
    }
    for i in 0..1024usize {
        let h = i * 4;
        let e = u32::from_be_bytes([bytes[h], bytes[h + 1], bytes[h + 2], bytes[h + 3]]);
        let offset = (e >> 8) as usize; // sectores de 4 KiB
        let count = (e & 0xff) as usize;
        if offset < 2 || count == 0 {
            continue;
        }
        let start = offset * 4096;
        if start + 5 > bytes.len() {
            continue;
        }
        let len =
            u32::from_be_bytes([bytes[start], bytes[start + 1], bytes[start + 2], bytes[start + 3]])
                as usize;
        if len == 0 {
            continue;
        }
        let ctype = bytes[start + 4];
        if ctype & 0x80 != 0 {
            continue; // chunk externo (.mcc): raro, se omite
        }
        let data_start = start + 5;
        let data_end = start + 4 + len; // len incluye el byte de compresión
        if data_end > bytes.len() {
            continue;
        }
        let comp = &bytes[data_start..data_end];
        let mut out = Vec::new();
        let ok = match ctype {
            1 => flate2::read::GzDecoder::new(comp).read_to_end(&mut out).is_ok(),
            2 => flate2::read::ZlibDecoder::new(comp).read_to_end(&mut out).is_ok(),
            3 => {
                out.extend_from_slice(comp);
                true
            }
            _ => false,
        };
        if ok && out.windows(PROV_KEY.len()).any(|w| w == PROV_KEY) {
            return Ok(true);
        }
    }
    Ok(false)
}

/// Cierra el juego con GRACIA y solo escala a la fuerza como último recurso.
///
/// `taskkill` SIN `/F` envía WM_CLOSE a las ventanas del proceso — idéntico a
/// pulsar la X de Minecraft: dispara el guardado-y-salida normal y cierra los
/// region files limpiamente. Matar en seco (`/F`, TerminateProcess) a media
/// escritura de un `.mca` deja chunks "en ubicación equivocada [0,0]" (la
/// corrupción de `New World`, ahora histórica). Espera a que el proceso muera
/// por su cuenta; si no cierra en 60 s (diálogo abierto, cuelgue), escala a `/F`.
/// Regenera el mundo DESECHABLE `mksa-test` desde el mundo PLANTILLA
/// `mksa-test-template` antes de cada lanzamiento. La plantilla la hace el usuario
/// a mano (con bloques de mods) y el harness NUNCA la abre — así ninguna corrupción
/// accidental quema ese trabajo. Defensa en profundidad junto a `quiesce` (que ya
/// evita la corrupción de raíz): si algo se colara, solo muere la copia.
fn reset_test_world(instancia: &str) {
    let inst = instances::read_instance(Path::new(instancia)).expect("instancia");
    let saves = Path::new(&inst.minecraft_dir).join("saves");
    let template = saves.join("mksa-test-template");
    let world = saves.join("mksa-test");
    check(
        template.is_dir(),
        &format!("plantilla 'mksa-test-template' presente ({})", template.display()),
    );
    if world.exists() {
        std::fs::remove_dir_all(&world).expect("borrar mundo desechable previo");
    }
    copy_dir_all(&template, &world).expect("copiar plantilla → mksa-test");
    println!("       mundo desechable 'mksa-test' regenerado desde plantilla (tu trabajo queda intacto)");
}

/// Copia recursiva de un directorio (std no la trae). Suficiente para un mundo de MC.
fn copy_dir_all(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let from = entry.path();
        let to = dst.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir_all(&from, &to)?;
        } else {
            std::fs::copy(&from, &to)?;
        }
    }
    Ok(())
}

/// Espera a que el churn de chunks se calme antes de cerrar el juego.
///
/// Es el arreglo de raíz de la corrupción, no un parche: cerrar a media carga de
/// chunks atasca el apagado de Minecraft (salta su *shutdown watchdog*), lo que
/// fuerza el escalado a `taskkill /F` de `kill_pid` — y matar en seco a media
/// escritura de un `.mca` deja chunks en `[0,0]` (la corrupción de `New World`).
/// Con el mundo en reposo —la cuenta `written` de `ledger.stats` deja de crecer—
/// el apagado de MC termina en segundos y `WM_CLOSE` guarda y sale limpio (es lo
/// que ya pasaba en ledger2, que cerraba con el mundo quieto). Así el `/F` deja de
/// dispararse en el camino normal. Tope de 120 s para no colgar el test si nunca
/// cuaja (mundo permanentemente activo); en ese caso cierra igual y el lecho de
/// seguridad de `kill_pid` decide.
fn quiesce(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: &mut u64,
) {
    println!("  … esperando a que el mundo se calme (churn de chunks) antes de cerrar");
    let cap = std::time::Instant::now() + std::time::Duration::from_secs(120);
    let mut last = -1i64;
    let mut stable = 0u32;
    loop {
        let (w, _, _) = ledger_stats(s, reader, *id);
        *id += 1;
        if w == last {
            stable += 1;
            if stable >= 3 {
                println!("       mundo en reposo (written={w} estable ~15s) → cierre limpio");
                return;
            }
        } else {
            stable = 0;
            last = w;
        }
        if std::time::Instant::now() >= cap {
            println!("       ⚠ no se estabilizó en 120s; cierro igualmente (written={w})");
            return;
        }
        std::thread::sleep(std::time::Duration::from_secs(5));
    }
}

fn kill_pid(pid: u32) {
    println!("  … cerrando pid {pid} con gracia (WM_CLOSE → guardado limpio)");
    let _ = std::process::Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T"])
        .output();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(60);
    while std::time::Instant::now() < deadline {
        if !launch::pid_alive(pid) {
            println!("  … cerrado limpio (region files a salvo)");
            return;
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
    println!("  ⚠ no cerró en 60s; escalando a taskkill /F (riesgo de corrupción)");
    let _ = std::process::Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/F", "/T"])
        .output();
}

fn request(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: u64,
    method: &str,
) -> serde_json::Value {
    request_p(s, reader, id, method, serde_json::json!({}))
}

fn request_p(
    s: &mut std::net::TcpStream,
    reader: &mut BufReader<std::net::TcpStream>,
    id: u64,
    method: &str,
    params: serde_json::Value,
) -> serde_json::Value {
    let req = serde_json::json!({ "t": "req", "id": id, "m": method, "p": params });
    s.write_all(format!("{req}\n").as_bytes()).unwrap();
    loop {
        let v = read_msg(reader);
        if v["t"] == "res" && v["id"] == id {
            return v;
        }
    }
}

fn read_msg(reader: &mut BufReader<std::net::TcpStream>) -> serde_json::Value {
    let mut line = String::new();
    if reader.read_line(&mut line).unwrap_or(0) == 0 {
        check(false, "conexión IPC viva");
    }
    serde_json::from_str(&line).expect("ndjson")
}
