// Evita la consola en Windows en release.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    mksa_launcher_lib::run()
}
