use serde::Serialize;
use std::fs;
use std::path::{Path, PathBuf};

/// Una instancia detectada por estructura de carpetas: cualquier directorio
/// que contenga `minecraft/` o `.minecraft/`. Los metadatos de Prism/MultiMC
/// (instance.cfg, mmc-pack.json) se leen si existen, pero no son requisito.
#[derive(Serialize, Clone, Debug)]
pub struct Instance {
    pub name: String,
    pub path: String,
    pub minecraft_dir: String,
    pub mc_version: Option<String>,
    pub loader_uid: Option<String>,
    pub loader_name: Option<String>,
    pub loader_version: Option<String>,
    pub components: Vec<Component>,
    pub mod_count: usize,
    pub java_path: Option<String>,
    pub max_mem_mb: u32,
    pub min_mem_mb: u32,
}

#[derive(Serialize, Clone, Debug)]
pub struct Component {
    pub uid: String,
    pub version: String,
}

fn loader_display_name(uid: &str) -> Option<&'static str> {
    match uid {
        "net.fabricmc.fabric-loader" => Some("Fabric"),
        "net.minecraftforge" => Some("Forge"),
        "net.neoforged" => Some("NeoForge"),
        "org.quiltmc.quilt-loader" => Some("Quilt"),
        _ => None,
    }
}

pub fn scan(root: &str) -> Result<Vec<Instance>, String> {
    let root = Path::new(root);
    if !root.is_dir() {
        return Err("La ruta no es válida o no existe.".into());
    }
    let mut out = Vec::new();
    for entry in fs::read_dir(root).map_err(|e| e.to_string())?.flatten() {
        let dir = entry.path();
        if !dir.is_dir() {
            continue;
        }
        if let Some(inst) = read_instance(&dir) {
            out.push(inst);
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(out)
}

pub fn read_instance(dir: &Path) -> Option<Instance> {
    let minecraft_dir = ["minecraft", ".minecraft"]
        .iter()
        .map(|d| dir.join(d))
        .find(|p| p.is_dir())?;

    let cfg = parse_cfg(&dir.join("instance.cfg"));
    let components = parse_mmc_pack(&dir.join("mmc-pack.json"));

    let mc_version = components
        .iter()
        .find(|c| c.uid == "net.minecraft")
        .map(|c| c.version.clone());
    let loader = components
        .iter()
        .find(|c| loader_display_name(&c.uid).is_some());

    let mods_dir = minecraft_dir.join("mods");
    let mod_count = fs::read_dir(&mods_dir)
        .map(|rd| {
            rd.flatten()
                .filter(|e| e.path().extension().is_some_and(|x| x == "jar"))
                .count()
        })
        .unwrap_or(0);

    let folder_name = dir.file_name()?.to_string_lossy().to_string();
    Some(Instance {
        name: cfg.get("name").cloned().unwrap_or(folder_name),
        path: dir.to_string_lossy().to_string(),
        minecraft_dir: minecraft_dir.to_string_lossy().to_string(),
        mc_version,
        loader_uid: loader.map(|c| c.uid.clone()),
        loader_name: loader
            .and_then(|c| loader_display_name(&c.uid))
            .map(String::from),
        loader_version: loader.map(|c| c.version.clone()),
        components,
        mod_count,
        java_path: cfg.get("JavaPath").filter(|s| !s.is_empty()).cloned(),
        max_mem_mb: cfg
            .get("MaxMemAlloc")
            .and_then(|s| s.parse().ok())
            .unwrap_or(4096),
        min_mem_mb: cfg
            .get("MinMemAlloc")
            .and_then(|s| s.parse().ok())
            .unwrap_or(512),
    })
}

/// instance.cfg es un INI plano de Qt: claves `k=v`, secciones que ignoramos.
fn parse_cfg(path: &Path) -> std::collections::HashMap<String, String> {
    let mut map = std::collections::HashMap::new();
    if let Ok(text) = fs::read_to_string(path) {
        for line in text.lines() {
            let line = line.trim();
            if line.starts_with('[') || line.is_empty() {
                continue;
            }
            if let Some((k, v)) = line.split_once('=') {
                map.insert(k.trim().to_string(), v.trim().to_string());
            }
        }
    }
    map
}

fn parse_mmc_pack(path: &Path) -> Vec<Component> {
    let Ok(text) = fs::read_to_string(path) else {
        return Vec::new();
    };
    let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) else {
        return Vec::new();
    };
    json["components"]
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|c| {
                    Some(Component {
                        uid: c["uid"].as_str()?.to_string(),
                        version: c["version"].as_str()?.to_string(),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

pub fn mksa_dir(instance_path: &str) -> PathBuf {
    Path::new(instance_path).join("mksa")
}
