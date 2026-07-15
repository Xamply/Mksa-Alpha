pub mod instances;
pub mod ipc;
pub mod jvm;
pub mod launch;
pub mod meta;

use launch::{Games, LaunchResult};
use tauri::{AppHandle, Runtime, State};

#[tauri::command]
fn scan_instances(root: String) -> Result<Vec<instances::Instance>, String> {
    instances::scan(&root)
}

#[tauri::command]
fn launch_instance<R: Runtime>(
    app: AppHandle<R>,
    games: State<'_, Games>,
    path: String,
) -> Result<LaunchResult, String> {
    launch::launch(app, games.inner().clone(), path)
}

/// Fase 3, corte 1: relanza la instancia con un mod desactivado (cierra el juego
/// vivo, excluye sus jar de mods/, lanza un proceso nuevo sobre el mismo mundo).
/// `disable_jars` son rutas de `state.mods[].files`.
#[tauri::command]
fn relaunch_disable<R: Runtime>(
    app: AppHandle<R>,
    games: State<'_, Games>,
    path: String,
    disable_jars: Vec<String>,
) -> Result<LaunchResult, String> {
    launch::relaunch_disable(app, games.inner().clone(), path, disable_jars)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(Games::default())
        .invoke_handler(tauri::generate_handler![
            scan_instances,
            launch_instance,
            relaunch_disable
        ])
        .run(tauri::generate_context!())
        .expect("error al arrancar la aplicación");
}
