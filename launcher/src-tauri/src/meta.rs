//! Resolución de artefactos y classpath a partir del meta formato MultiMC.
//!
//! Fuentes, en orden: meta cacheado de Prism → caché propia de MKSA →
//! descarga de meta.prismlauncher.org. Los jars se buscan en las libraries
//! de Prism y en la caché propia; lo que falte se descarga con verificación
//! sha1. Reutilizar Prism es una optimización, no una dependencia.

use crate::instances::Instance;
use serde_json::Value;
use std::fs;
use std::path::PathBuf;

pub struct ResolvedLaunch {
    pub classpath: Vec<PathBuf>,
    pub main_class: String,
    pub mc_args_template: String,
    pub asset_index_id: String,
    pub assets_dir: PathBuf,
    pub mc_version: String,
    pub java_majors: Vec<i64>,
    pub client_jar: Option<PathBuf>,
    pub forgewrapper: Option<ForgeWrapper>,
}

/// Forge moderno/NeoForge se lanza vía ForgeWrapper (así lo empaqueta el meta
/// de Prism): mainClass es el wrapper y estas rutas viajan como propiedades
/// `-Dforgewrapper.*`. El wrapper corre el instalador solo si sus salidas no
/// están ya bajo `libraries_dir`.
pub struct ForgeWrapper {
    pub installer: PathBuf,
    pub libraries_dir: PathBuf,
}

pub fn prism_root() -> Option<PathBuf> {
    let appdata = std::env::var("APPDATA").ok()?;
    let p = PathBuf::from(appdata).join("PrismLauncher");
    p.is_dir().then_some(p)
}

pub fn mksa_cache_root() -> PathBuf {
    let base = std::env::var("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("."));
    base.join("MKSA")
}

/// progreso: callback hacia la UI (etapa, detalle)
pub fn resolve(
    instance: &Instance,
    progress: &dyn Fn(&str),
) -> Result<ResolvedLaunch, String> {
    if instance.components.is_empty() {
        return Err(
            "La instancia no tiene mmc-pack.json; el lanzamiento de instancias sin metadatos llegará más adelante."
                .into(),
        );
    }

    // Cargar el meta JSON de cada componente y ordenarlos por 'order'.
    let mut metas: Vec<Value> = Vec::new();
    for c in &instance.components {
        progress(&format!("resolviendo meta de {}", c.uid));
        metas.push(load_component_meta(&c.uid, &c.version)?);
    }
    metas.sort_by_key(|m| m["order"].as_i64().unwrap_or(0));

    let minecraft = metas
        .iter()
        .find(|m| m["uid"] == "net.minecraft")
        .ok_or("mmc-pack.json no declara net.minecraft")?;

    // mainClass: el loader manda si lo define; si no, vanilla.
    let main_class = metas
        .iter()
        .filter(|m| m["uid"] != "net.minecraft")
        .filter_map(|m| m["mainClass"].as_str())
        .last()
        .or(minecraft["mainClass"].as_str())
        .ok_or("ningún componente define mainClass")?
        .to_string();

    // minecraftArguments: el loader manda si los redefine (Forge/NeoForge
    // añaden --launchTarget y los --fml.*); si no, los de vanilla.
    let mc_args_template = metas
        .iter()
        .filter(|m| m["uid"] != "net.minecraft")
        .filter_map(|m| m["minecraftArguments"].as_str())
        .last()
        .or(minecraft["minecraftArguments"].as_str())
        .ok_or("ningún componente trae minecraftArguments")?
        .to_string();

    let asset_index_id = minecraft["assetIndex"]["id"]
        .as_str()
        .ok_or("el meta de net.minecraft no trae assetIndex")?
        .to_string();

    let java_majors = minecraft["compatibleJavaMajors"]
        .as_array()
        .map(|a| a.iter().filter_map(|v| v.as_i64()).collect())
        .unwrap_or_default();

    // Assets: Fase 1 reutiliza los de Prism; el descargador de assets es trabajo futuro.
    let assets_dir = prism_root()
        .map(|p| p.join("assets"))
        .filter(|p| p.join("indexes").join(format!("{asset_index_id}.json")).is_file())
        .ok_or(format!(
            "No se encontró el índice de assets {asset_index_id}. Por ahora MKSA reutiliza los assets de Prism Launcher: abre la instancia una vez en Prism para descargarlos."
        ))?;

    // Recolectar artefactos de todos los componentes con dedup por
    // group:artifact(:classifier), no por GAV completo: el mismo artefacto
    // puede venir en versiones distintas (vanilla 1.21.5 trae asm 9.6 y
    // NeoForge exige 9.8) y el componente de mayor order gana — BootstrapLauncher
    // aborta si el classpath contradice su module path. La posición en el
    // classpath es la de la primera aparición.
    let mut key_order: Vec<String> = Vec::new();
    let mut chosen: std::collections::HashMap<String, Value> = std::collections::HashMap::new();
    for m in &metas {
        if let Some(libs) = m["libraries"].as_array() {
            for lib in libs {
                if !rules_allow(lib) {
                    continue;
                }
                let name = lib["name"].as_str().unwrap_or_default();
                if name.is_empty() {
                    continue;
                }
                let key = dedup_key(name)?;
                if !chosen.contains_key(&key) {
                    key_order.push(key.clone());
                }
                chosen.insert(key, lib.clone());
            }
        }
    }
    let mut classpath: Vec<PathBuf> = Vec::new();
    for key in &key_order {
        let lib = &chosen[key];
        progress(&format!("artefacto {}", lib["name"].as_str().unwrap_or_default()));
        classpath.push(locate_or_download(lib)?);
    }
    // El jar del cliente va al final del classpath.
    let main_jar = &minecraft["mainJar"];
    let mut client_jar = None;
    if main_jar.is_object() {
        progress("jar del cliente");
        let p = locate_or_download(main_jar)?;
        classpath.push(p.clone());
        client_jar = Some(p);
    }

    let forgewrapper = if main_class.contains("forgewrapper") {
        Some(resolve_forgewrapper(&metas, progress)?)
    } else {
        None
    };

    Ok(ResolvedLaunch {
        classpath,
        main_class,
        mc_args_template,
        asset_index_id,
        assets_dir,
        mc_version: instance.mc_version.clone().unwrap_or_default(),
        java_majors,
        client_jar,
        forgewrapper,
    })
}

/// Resuelve los mavenFiles del loader (artefactos del instalador, fuera del
/// classpath) y garantiza que todos cuelguen de UN único root de libraries,
/// que es lo que ForgeWrapper recibe como `librariesDir`: se prefiere el root
/// donde quedó el instalador y los descarriados se copian dentro.
fn resolve_forgewrapper(
    metas: &[Value],
    progress: &dyn Fn(&str),
) -> Result<ForgeWrapper, String> {
    let mut installer: Option<PathBuf> = None;
    let mut files: Vec<(String, PathBuf)> = Vec::new();
    for m in metas {
        if let Some(maven_files) = m["mavenFiles"].as_array() {
            for f in maven_files {
                let name = f["name"].as_str().unwrap_or_default();
                progress(&format!("artefacto del instalador {name}"));
                let p = locate_or_download(f)?;
                if name.ends_with(":installer") {
                    installer = Some(p.clone());
                }
                files.push((maven_path(name)?, p));
            }
        }
    }
    let installer = installer.ok_or("el meta del loader no trae el jar del instalador")?;

    let prism_libs = prism_root().map(|p| p.join("libraries"));
    let libraries_dir = match &prism_libs {
        Some(p) if installer.starts_with(p) => p.clone(),
        _ => mksa_cache_root().join("libraries"),
    };
    for (rel, p) in &files {
        if !p.starts_with(&libraries_dir) {
            let dest = libraries_dir.join(rel);
            if !dest.is_file() {
                if let Some(parent) = dest.parent() {
                    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
                }
                fs::copy(p, &dest).map_err(|e| {
                    format!("no se pudo copiar {} al root de libraries: {e}", p.display())
                })?;
            }
        }
    }
    Ok(ForgeWrapper { installer, libraries_dir })
}

fn load_component_meta(uid: &str, version: &str) -> Result<Value, String> {
    let file = format!("{version}.json");
    let mut candidates = Vec::new();
    if let Some(prism) = prism_root() {
        candidates.push(prism.join("meta").join(uid).join(&file));
    }
    let cached = mksa_cache_root().join("meta").join(uid).join(&file);
    candidates.push(cached.clone());

    for c in &candidates {
        if let Ok(text) = fs::read_to_string(c) {
            if let Ok(v) = serde_json::from_str(&text) {
                return Ok(v);
            }
        }
    }
    // Descarga del meta de Prism (mismo formato que su caché local).
    let url = format!("https://meta.prismlauncher.org/v1/{uid}/{file}");
    let text = http_get_text(&url)
        .map_err(|e| format!("no se pudo obtener el meta de {uid} {version}: {e}"))?;
    let v: Value =
        serde_json::from_str(&text).map_err(|e| format!("meta inválido de {uid}: {e}"))?;
    if let Some(parent) = cached.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let _ = fs::write(&cached, &text);
    Ok(v)
}

/// Reglas estilo Mojang: si hay reglas, por defecto se deniega y cada regla
/// que matchea fija la acción. Host objetivo: windows x86_64.
fn rules_allow(lib: &Value) -> bool {
    let Some(rules) = lib["rules"].as_array() else {
        return true;
    };
    let mut allowed = false;
    for rule in rules {
        let matches = match rule["os"]["name"].as_str() {
            None => true,
            Some(os) => os == host_os_name(),
        };
        if matches {
            allowed = rule["action"].as_str() == Some("allow");
        }
    }
    allowed
}

fn host_os_name() -> &'static str {
    #[cfg(target_os = "windows")]
    {
        "windows"
    }
    #[cfg(target_os = "macos")]
    {
        "osx"
    }
    #[cfg(target_os = "linux")]
    {
        "linux"
    }
}

/// Identidad de artefacto sin versión: "group:artifact[:classifier][@ext]".
fn dedup_key(gav: &str) -> Result<String, String> {
    let (gav, ext) = match gav.rsplit_once('@') {
        Some((g, e)) => (g, e),
        None => (gav, "jar"),
    };
    let parts: Vec<&str> = gav.split(':').collect();
    if parts.len() < 3 {
        return Err(format!("coordenada maven inválida: {gav}"));
    }
    let classifier = parts.get(3).map(|c| format!(":{c}")).unwrap_or_default();
    Ok(format!("{}:{}{classifier}@{ext}", parts[0], parts[1]))
}

/// "group:artifact:version[:classifier][@ext]" → ruta maven relativa.
fn maven_path(gav: &str) -> Result<String, String> {
    let (gav, ext) = match gav.rsplit_once('@') {
        Some((g, e)) => (g, e),
        None => (gav, "jar"),
    };
    let parts: Vec<&str> = gav.split(':').collect();
    if parts.len() < 3 {
        return Err(format!("coordenada maven inválida: {gav}"));
    }
    let (group, artifact, version) = (parts[0], parts[1], parts[2]);
    let classifier = parts.get(3).map(|c| format!("-{c}")).unwrap_or_default();
    Ok(format!(
        "{}/{artifact}/{version}/{artifact}-{version}{classifier}.{ext}",
        group.replace('.', "/")
    ))
}

fn locate_or_download(lib: &Value) -> Result<PathBuf, String> {
    let gav = lib["name"].as_str().ok_or("librería sin name")?;
    let rel = maven_path(gav)?;

    let mut roots = Vec::new();
    if let Some(prism) = prism_root() {
        roots.push(prism.join("libraries"));
    }
    let cache = mksa_cache_root().join("libraries");
    roots.push(cache.clone());

    for root in &roots {
        let p = root.join(&rel);
        if p.is_file() {
            return Ok(p);
        }
    }

    // No está en disco: descargar a la caché propia.
    let (url, sha1) = if let Some(artifact) = lib["downloads"]["artifact"].as_object() {
        (
            artifact["url"].as_str().unwrap_or_default().to_string(),
            artifact.get("sha1").and_then(|s| s.as_str()).map(String::from),
        )
    } else if let Some(base) = lib["url"].as_str() {
        (format!("{}/{}", base.trim_end_matches('/'), rel), None)
    } else {
        return Err(format!("{gav}: no está en disco y el meta no trae URL de descarga"));
    };
    if url.is_empty() {
        return Err(format!("{gav}: URL de descarga vacía"));
    }

    let dest = cache.join(&rel);
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let bytes = http_get_bytes(&url).map_err(|e| format!("descarga de {gav} falló: {e}"))?;
    if let Some(expect) = sha1 {
        let mut h = sha1_smol::Sha1::new();
        h.update(&bytes);
        let got = h.digest().to_string();
        if got != expect {
            return Err(format!("{gav}: sha1 no coincide (esperado {expect}, obtenido {got})"));
        }
    }
    fs::write(&dest, &bytes).map_err(|e| e.to_string())?;
    Ok(dest)
}

fn http_get_text(url: &str) -> Result<String, String> {
    ureq::get(url)
        .timeout(std::time::Duration::from_secs(30))
        .call()
        .map_err(|e| e.to_string())?
        .into_string()
        .map_err(|e| e.to_string())
}

fn http_get_bytes(url: &str) -> Result<Vec<u8>, String> {
    let resp = ureq::get(url)
        .timeout(std::time::Duration::from_secs(120))
        .call()
        .map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    use std::io::Read;
    resp.into_reader()
        .take(512 * 1024 * 1024)
        .read_to_end(&mut out)
        .map_err(|e| e.to_string())?;
    Ok(out)
}
