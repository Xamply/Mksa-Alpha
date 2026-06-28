//! Resolución de JVM según el plan de Fase 1:
//! JavaPath de la instancia → JAVA_HOME → rutas estándar del SO → descarga
//! silenciosa de JBR (el fork de OpenJDK de JetBrains con DCEVM, que la Fase 3
//! necesitará para el des-mixineo vía -XX:+AllowEnhancedClassRedefinition).

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Command;

#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub struct ResolvedJava {
    /// Ejecutable con el que se lanza el juego (javaw en Windows: sin consola).
    pub launch_exe: PathBuf,
    pub major: i64,
}

// JBR fijado para la descarga silenciosa. Subir de versión = cambiar estas
// cuatro constantes (el checksum sale de <url>.checksum en la release).
const JBR_MAJOR: i64 = 21;
const JBR_DIR_NAME: &str = "jbr-21.0.11-b1163.116";
const JBR_URL: &str =
    "https://cache-redirector.jetbrains.com/intellij-jbr/jbr-21.0.11-windows-x64-b1163.116.tar.gz";
const JBR_SHA512: &str = "1b5a3b602bf8096263a87d60a524fdd422696a0ff4af288e554dde8b11a3178c99f5a8d9161e9cc6b318790330fa75fdaa597d23551cbfc6a9387afbc4f51626";

pub fn resolve(
    instance_java: Option<&str>,
    required_majors: &[i64],
    progress: &dyn Fn(&str),
) -> Result<ResolvedJava, String> {
    let mut candidates: Vec<PathBuf> = Vec::new();

    if let Some(p) = instance_java {
        candidates.push(PathBuf::from(p));
    }
    if let Ok(home) = std::env::var("JAVA_HOME") {
        candidates.push(Path::new(&home).join("bin").join(exe_name()));
    }
    candidates.extend(standard_locations());

    let mut found_any = false;
    for cand in candidates {
        if !cand.is_file() {
            continue;
        }
        found_any = true;
        let Some(major) = probe_major(&cand) else {
            continue;
        };
        if required_majors.is_empty() || required_majors.contains(&major) {
            return Ok(ResolvedJava { launch_exe: cand, major });
        }
    }

    if cfg!(windows) && required_majors.contains(&JBR_MAJOR) {
        return ensure_jbr(progress);
    }

    Err(format!(
        "No se encontró una JVM compatible (se requiere Java {:?}{}) y la descarga de JBR solo cubre Java {JBR_MAJOR} en Windows.",
        required_majors,
        if found_any { "; hay JVMs instaladas pero de otra versión" } else { "" }
    ))
}

/// Garantiza un JBR local en %LOCALAPPDATA%/MKSA/jbr, descargándolo si falta.
/// Descarga a archivo temporal con sha512 al vuelo, extrae a un directorio
/// de staging y renombra al final: una descarga interrumpida nunca deja un
/// JBR a medias que parezca válido.
pub fn ensure_jbr(progress: &dyn Fn(&str)) -> Result<ResolvedJava, String> {
    let jbr_root = crate::meta::mksa_cache_root().join("jbr").join(JBR_DIR_NAME);
    let launch_exe = jbr_root.join("bin").join(exe_name());
    if launch_exe.is_file() {
        return Ok(ResolvedJava { launch_exe, major: JBR_MAJOR });
    }

    let parent = jbr_root.parent().unwrap().to_path_buf();
    std::fs::create_dir_all(&parent).map_err(|e| format!("creando {parent:?}: {e}"))?;
    let tgz_path = parent.join(format!("{JBR_DIR_NAME}.tar.gz.part"));
    let staging = parent.join(format!("{JBR_DIR_NAME}.staging"));
    let _ = std::fs::remove_dir_all(&staging);

    progress(&format!("descargando JBR {JBR_MAJOR} (~45 MB, solo la primera vez)"));
    download_with_sha512(JBR_URL, &tgz_path, JBR_SHA512, progress)?;

    progress("extrayendo JBR");
    let file = std::fs::File::open(&tgz_path).map_err(|e| e.to_string())?;
    tar::Archive::new(flate2::read::GzDecoder::new(file))
        .unpack(&staging)
        .map_err(|e| format!("extrayendo JBR: {e}"))?;
    let _ = std::fs::remove_file(&tgz_path);

    // El tar lleva un único directorio raíz cuyo nombre puede variar entre
    // releases; localizamos el que contenga bin/, sea la raíz o un hijo.
    let inner = if staging.join("bin").join(exe_name()).is_file() {
        staging.clone()
    } else {
        std::fs::read_dir(&staging)
            .map_err(|e| e.to_string())?
            .flatten()
            .map(|e| e.path())
            .find(|p| p.join("bin").join(exe_name()).is_file())
            .ok_or("el archivo de JBR no contiene bin/ reconocible")?
    };
    std::fs::rename(&inner, &jbr_root).map_err(|e| format!("instalando JBR: {e}"))?;
    let _ = std::fs::remove_dir_all(&staging);

    if !launch_exe.is_file() {
        return Err("JBR extraído pero sin el ejecutable esperado".into());
    }
    progress(&format!("JBR {JBR_MAJOR} listo"));
    Ok(ResolvedJava { launch_exe, major: JBR_MAJOR })
}

fn download_with_sha512(
    url: &str,
    dest: &Path,
    expected: &str,
    progress: &dyn Fn(&str),
) -> Result<(), String> {
    use sha2::Digest;
    let resp = ureq::get(url)
        .timeout(std::time::Duration::from_secs(600))
        .call()
        .map_err(|e| format!("descargando JBR: {e}"))?;
    let total = resp
        .header("Content-Length")
        .and_then(|s| s.parse::<u64>().ok());
    let mut reader = resp.into_reader();
    let mut out = std::fs::File::create(dest).map_err(|e| e.to_string())?;
    let mut hasher = sha2::Sha512::new();
    let mut buf = [0u8; 64 * 1024];
    let mut done: u64 = 0;
    let mut last_pct = 0;
    loop {
        let n = reader.read(&mut buf).map_err(|e| format!("descargando JBR: {e}"))?;
        if n == 0 {
            break;
        }
        std::io::Write::write_all(&mut out, &buf[..n]).map_err(|e| e.to_string())?;
        hasher.update(&buf[..n]);
        done += n as u64;
        if let Some(t) = total {
            let pct = (done * 100 / t) as u32;
            if pct >= last_pct + 10 {
                last_pct = pct;
                progress(&format!("descargando JBR: {pct}%"));
            }
        }
    }
    let got = format!("{:x}", hasher.finalize());
    if got != expected {
        let _ = std::fs::remove_file(dest);
        return Err(format!("sha512 de JBR no coincide (esperado {expected}, obtenido {got})"));
    }
    Ok(())
}

fn exe_name() -> &'static str {
    if cfg!(windows) { "javaw.exe" } else { "java" }
}

fn standard_locations() -> Vec<PathBuf> {
    let mut out = Vec::new();
    #[cfg(windows)]
    {
        let mut roots: Vec<PathBuf> = vec![
            PathBuf::from("C:/Program Files/Eclipse Adoptium"),
            PathBuf::from("C:/Program Files/Java"),
            PathBuf::from("C:/Program Files/Microsoft"),
            PathBuf::from("C:/Program Files/Zulu"),
        ];
        // Runtimes que Prism ya descargó (java-runtime-delta, etc.).
        if let Ok(appdata) = std::env::var("APPDATA") {
            roots.push(PathBuf::from(appdata).join("PrismLauncher").join("java"));
        }
        for root in roots {
            if let Ok(rd) = std::fs::read_dir(&root) {
                for e in rd.flatten() {
                    let bin = e.path().join("bin").join("javaw.exe");
                    if bin.is_file() {
                        out.push(bin);
                    }
                }
            }
        }
    }
    #[cfg(not(windows))]
    {
        for p in ["/usr/lib/jvm", "/Library/Java/JavaVirtualMachines"] {
            if let Ok(rd) = std::fs::read_dir(p) {
                for e in rd.flatten() {
                    for sub in ["bin/java", "Contents/Home/bin/java"] {
                        let bin = e.path().join(sub);
                        if bin.is_file() {
                            out.push(bin);
                        }
                    }
                }
            }
        }
    }
    out
}

/// `javaw -version` no imprime nada (sin consola); sondear siempre con java.exe.
fn probe_exe(java: &Path) -> PathBuf {
    if java.file_name().is_some_and(|n| n == "javaw.exe") {
        java.with_file_name("java.exe")
    } else {
        java.to_path_buf()
    }
}

fn probe_major(java: &Path) -> Option<i64> {
    let mut cmd = Command::new(probe_exe(java));
    cmd.arg("-version");
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);
    let out = cmd.output().ok()?;
    let text = String::from_utf8_lossy(&out.stderr).to_string()
        + &String::from_utf8_lossy(&out.stdout);
    // formato: openjdk version "21.0.7" / java version "1.8.0_392"
    let quoted = text.split('"').nth(1)?;
    let mut nums = quoted.split(['.', '_', '-']).filter_map(|s| s.parse::<i64>().ok());
    let first = nums.next()?;
    Some(if first == 1 { nums.next().unwrap_or(8) } else { first })
}
