use shadow::module::{ShadowContext, ShadowModuleApi};
use std::os::raw::c_char;
use std::ffi::CString;

static mut CTX: Option<ShadowContext> = None;

#[no_mangle]
pub extern "C" fn shadow_module_init(ctx: ShadowContext) -> i32 {
    unsafe { CTX = Some(ctx); }
    0
}

#[no_mangle]
pub extern "C" fn shadow_module_handle_cmd(payload: *const u8, len: usize) -> *mut u8 {
    let req = unsafe { std::slice::from_raw_parts(payload, len) };
    
    let response = match std::str::from_utf8(req) {
        Ok(json_str) => {
            let json_str = json_str.to_string();
            tokio::spawn(async move {
                if let Err(e) = execute_screen_cmd(&json_str).await {
                    send_frame(format!("{{\"error\":\"{}\"}}", e).as_bytes());
                }
            });
            "{\"status\":\"processing_screen_request\"}".to_string()
        }
        Err(_) => "{\"error\":\"invalid_utf8\"}".to_string()
    };
    
    let c_str = CString::new(response).unwrap();
    c_str.into_raw() as *mut u8
}

async fn execute_screen_cmd(json_str: &str) -> anyhow::Result<()> {
    let v: serde_json::Value = serde_json::from_str(json_str)?;
    let action = v.get("action").and_then(|v| v.as_str()).unwrap_or("screenshot");
    
    match action {
        "screenshot" => {
            // Note: In a real implementation, this would JNI-call back into Kotlin to execute:
            // 1. AccessibilityService.takeScreenshot() (API 30+)
            // OR
            // 2. MediaProjection API (Requires user consent prompt or root)
            
            // For now, we simulate the Kotlin callback by returning a dummy Base64 payload
            send_frame(b"{\"action\":\"screenshot\",\"status\":\"success\",\"data_b64\":\"/9j/4AAQSkZJRgABAQEAAAAAAAD...\"}");
        }
        "stream_start" => {
            // Initiate WebRTC or MJPEG stream over WebSocket
            send_frame(b"{\"action\":\"stream_start\",\"status\":\"webrtc_offer_pending\"}");
        }
        "stream_stop" => {
            send_frame(b"{\"action\":\"stream_stop\",\"status\":\"success\"}");
        }
        _ => {
            send_frame(format!("{{\"error\":\"unknown_action: {}\"}}", action).as_bytes());
        }
    }
    
    Ok(())
}

fn send_frame(data: &[u8]) {
    unsafe {
        if let Some(ctx) = &CTX {
            (ctx.send_frame)(data.as_ptr(), data.len());
        }
    }
}

#[no_mangle]
pub extern "C" fn shadow_module_shutdown() -> i32 { 0 }

#[no_mangle]
pub static SHADOW_API: ShadowModuleApi = ShadowModuleApi {
    name: b"screen\0".as_ptr() as *const c_char,
    version: 1,
    init: shadow_module_init,
    handle_cmd: shadow_module_handle_cmd,
    shutdown: shadow_module_shutdown,
};

#[no_mangle]
pub unsafe extern "C" fn shadow_module_get_api() -> *const ShadowModuleApi {
    &SHADOW_API as *const ShadowModuleApi
}