//! Cliente IPC del launcher (protocolo v1, subconjunto F1):
//! espera el archivo de contacto, hace el hello, mantiene ping, y reenvía
//! boot.stage/shutdown a la UI como eventos Tauri.

use crate::launch::{emit_game, now_ms, Games};
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, ErrorKind, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::process::Child;
use std::time::Duration;
use tauri::{AppHandle, Runtime};

pub fn supervise<R: Runtime>(
    app: AppHandle<R>,
    games: Games,
    instance: String,
    mksa_dir: PathBuf,
    token: String,
    mut child: Child,
) {
    let cleanup = |games: &Games| {
        games.lock().unwrap().remove(&instance);
        let _ = std::fs::remove_file(mksa_dir.join("launching.lock"));
    };

    // Fase de espera: agent.json debe aparecer antes de 120 s (el primer
    // arranque de una instancia con muchos mods puede tardar en llegar a premain).
    let contact = mksa_dir.join("agent.json");
    let deadline = now_ms() + 120_000;
    let port = loop {
        if let Ok(Some(status)) = child.try_wait() {
            emit_game(&app, &instance, "error", &[(
                "detail",
                format!("el juego terminó durante el arranque (código {status})"),
            )]);
            cleanup(&games);
            return;
        }
        if let Ok(text) = std::fs::read_to_string(&contact) {
            if let Ok(v) = serde_json::from_str::<Value>(&text) {
                if let Some(p) = v["port"].as_u64() {
                    break p as u16;
                }
            }
        }
        if now_ms() > deadline {
            emit_game(&app, &instance, "error", &[(
                "detail",
                "el agente no publicó su archivo de contacto en 120s".into(),
            )]);
            cleanup(&games);
            return;
        }
        std::thread::sleep(Duration::from_millis(400));
    };

    // El contacto existe: fin de la ventana ciega (§1.5 paso 4).
    let _ = std::fs::remove_file(mksa_dir.join("launching.lock"));

    match connect_and_pump(&app, &instance, port, &token, &mut child) {
        Ok(()) => emit_game(&app, &instance, "closed", &[]),
        Err(e) => {
            // El IPC cayó; el juego puede seguir vivo. Distinguimos.
            if matches!(child.try_wait(), Ok(Some(_))) {
                emit_game(&app, &instance, "closed", &[]);
            } else {
                emit_game(&app, &instance, "error", &[(
                    "detail",
                    format!("IPC desconectado ({e}); el juego sigue corriendo"),
                )]);
            }
        }
    }
    cleanup(&games);
    // Reaper: si el juego sigue vivo tras perder IPC, esperar evita zombies.
    let _ = child.wait();
}

fn connect_and_pump<R: Runtime>(
    app: &AppHandle<R>,
    instance: &str,
    port: u16,
    token: &str,
    child: &mut Child,
) -> Result<(), String> {
    let stream = TcpStream::connect_timeout(
        &std::net::SocketAddr::from(([127, 0, 0, 1], port)),
        Duration::from_secs(5),
    )
    .map_err(|e| format!("conexión al agente: {e}"))?;
    stream
        .set_read_timeout(Some(Duration::from_secs(10)))
        .map_err(|e| e.to_string())?;
    let mut writer = stream.try_clone().map_err(|e| e.to_string())?;
    let mut reader = BufReader::new(stream);

    let hello = json!({
        "t": "req", "id": 1, "m": "hello",
        "p": { "proto": 1, "side": "launcher", "version": env!("CARGO_PKG_VERSION"), "token": token }
    });
    send_line(&mut writer, &hello)?;

    let first = read_line_blocking(&mut reader)?;
    let v: Value = serde_json::from_str(&first).map_err(|e| e.to_string())?;
    let ok = &v["ok"];
    if !ok.is_object() {
        return Err(format!("hello rechazado: {}", v["err"]["code"].as_str().unwrap_or("?")));
    }
    let session = ok["session"].as_str().unwrap_or("?").to_string();
    emit_game(app, instance, "connected", &[("session", session)]);
    if let Some(stage) = ok["boot"].as_str() {
        emit_game(app, instance, "boot", &[("stage", stage.to_string())]);
    }

    // Bucle de bombeo: leer con timeout de 10s; cada timeout = momento de ping.
    let mut next_id: u64 = 2;
    let mut pending_pings: u32 = 0;
    let mut buf = String::new();
    loop {
        if let Ok(Some(_)) = child.try_wait() {
            // El proceso murió; el evt shutdown puede haberse perdido.
            return Ok(());
        }
        buf.clear();
        match reader.read_line(&mut buf) {
            Ok(0) => return Ok(()), // EOF: el agente cerró (shutdown ordenado)
            Ok(_) => {
                pending_pings = 0;
                handle_line(app, instance, buf.trim());
                if buf.contains("\"shutdown\"") {
                    return Ok(());
                }
            }
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                if pending_pings >= 3 {
                    return Err("3 pings sin respuesta".into());
                }
                let ping = json!({ "t": "req", "id": next_id, "m": "ping", "p": {} });
                next_id += 1;
                pending_pings += 1;
                send_line(&mut writer, &ping)?;
            }
            Err(e) => return Err(e.to_string()),
        }
    }
}

fn handle_line<R: Runtime>(app: &AppHandle<R>, instance: &str, line: &str) {
    let Ok(v) = serde_json::from_str::<Value>(line) else {
        return;
    };
    if v["t"] == "evt" {
        match v["m"].as_str() {
            Some("boot.stage") => {
                if let Some(stage) = v["p"]["stage"].as_str() {
                    emit_game(app, instance, "boot", &[("stage", stage.to_string())]);
                }
            }
            Some("boot.failed") => {
                let stage = v["p"]["stage"].as_str().unwrap_or("?");
                let error = v["p"]["error"].as_str().unwrap_or("?");
                emit_game(app, instance, "error", &[(
                    "detail",
                    format!("arranque fallido en {stage}: {error}; el juego puede seguir vivo"),
                )]);
            }
            Some("shutdown") => {
                // el bucle principal corta al ver shutdown
            }
            _ => {} // eventos desconocidos se ignoran (protocolo §2.1)
        }
    }
    // las res de ping solo resetean pending_pings (ya hecho en el bucle)
}

fn send_line(w: &mut TcpStream, v: &Value) -> Result<(), String> {
    let mut s = v.to_string();
    s.push('\n');
    w.write_all(s.as_bytes()).map_err(|e| e.to_string())
}

fn read_line_blocking(r: &mut BufReader<TcpStream>) -> Result<String, String> {
    let mut s = String::new();
    loop {
        match r.read_line(&mut s) {
            Ok(0) => return Err("conexión cerrada durante el hello".into()),
            Ok(_) => return Ok(s),
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => continue,
            Err(e) => return Err(e.to_string()),
        }
    }
}
