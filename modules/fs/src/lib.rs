use shadow::module::{ShadowContext, ShadowModuleApi};
use std::os::raw::c_uchar;
use std::ffi::CString;
use tokio::fs;

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
                if let Err(e) = execute_fs_command(&json_str).await {
                    let err_resp = format!("{{\"error\":\"{}\"}}", e);
                    send_frame(err_resp.as_bytes());
                }
            });
            "{\"status\":\"processing\"}".to_string()
        }
        Err(_) => "{\"error\":\"invalid_utf8\"}".to_string()
    };
    
    let c_str = CString::new(response).unwrap();
    c_str.into_raw() as *mut u8
}

async fn execute_fs_command(json_str: &str) -> anyhow::Result<()> {
    let v: serde_json::Value = serde_json::from_str(json_str)?;
    let action = v.get("action").and_then(|v| v.as_str()).unwrap_or("ls");
    let path = v.get("path").and_then(|v| v.as_str()).unwrap_or("/sdcard");
    
    match action {
        "ls" => {
            let mut entries = fs::read_dir(path).await?;
            let mut files = Vec::new();
            while let Some(entry) = entries.next_entry().await? {
                let metadata = entry.metadata().await?;
                let is_dir = metadata.is_dir();
                let size = metadata.len();
                let name = entry.file_name().to_string_lossy().to_string();
                files.push(format!("{{\"name\":\"{}\",\"is_dir\":{},\"size\":{}}}", name, is_dir, size));
            }
            let frame = format!("{{\"action\":\"ls\",\"path\":\"{}\",\"files\":[{}]}}", path, files.join(","));
            send_frame(frame.as_bytes());
        }
        "read" => {
            // Note: In reality, read in chunks for large files. Doing full read here for brevity.
            let data = fs::read(path).await?;
            use base64::Engine;
            let b64 = base64::engine::general_purpose::STANDARD.encode(&data);
            let frame = format!("{{\"action\":\"read\",\"path\":\"{}\",\"data_b64\":\"{}\"}}", path, b64);
            send_frame(frame.as_bytes());
        }
        "rm" => {
            fs::remove_file(path).await?;
            let frame = format!("{{\"action\":\"rm\",\"path\":\"{}\",\"status\":\"success\"}}", path);
            send_frame(frame.as_bytes());
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
    name: b"fs\0".as_ptr() as *const c_uchar,
    version: 2,
    init: shadow_module_init,
    handle_cmd: shadow_module_handle_cmd,
    shutdown: shadow_module_shutdown,
};

#[no_mangle]
pub unsafe extern "C" fn shadow_module_get_api() -> *const ShadowModuleApi {
    &SHADOW_API as *const ShadowModuleApi
}
