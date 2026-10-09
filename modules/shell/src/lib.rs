use shadow::module::{ShadowContext, ShadowModuleApi};
use std::os::raw::c_char;
use std::ffi::CString;
use std::process::Stdio;
use tokio::process::Command;
use tokio::io::{AsyncBufReadExt, BufReader};

static mut CTX: Option<ShadowContext> = None;
static mut SENDER: Option<tokio::sync::mpsc::UnboundedSender<Vec<u8>>> = None;

#[no_mangle]
pub extern "C" fn shadow_module_init(ctx: ShadowContext) -> i32 {
    unsafe { CTX = Some(ctx); }
    0
}

#[no_mangle]
pub extern "C" fn shadow_module_handle_cmd(payload: *const u8, len: usize) -> *mut u8 {
    let req = unsafe { std::slice::from_raw_parts(payload, len) };
    
    // Expect JSON: {"cmd": "ls -la", "session_id": "optional"}
    let response = match std::str::from_utf8(req) {
        Ok(json_str) => {
            // Spawn async task to execute command
            let json_str = json_str.to_string();
            tokio::spawn(async move {
                if let Err(e) = execute_shell_command(&json_str).await {
                    let err_resp = format!("{{\"error\":\"{}\"}}", e);
                    send_frame(err_resp.as_bytes());
                }
            });
            // Return immediate acknowledgment
            "{\"status\":\"executing\"}".to_string()
        }
        Err(_) => "{\"error\":\"invalid_utf8\"}".to_string()
    };
    
    let c_str = CString::new(response).unwrap();
    c_str.into_raw() as *mut u8
}

async fn execute_shell_command(json_str: &str) -> anyhow::Result<()> {
    let v: serde_json::Value = serde_json::from_str(json_str)?;
    let cmd_str = v.get("cmd").and_then(|v| v.as_str()).unwrap_or("sh");
    let session_id = v.get("session_id").and_then(|v| v.as_str()).unwrap_or("default");
    
    // Parse command (simple split for now, could use shell_words)
    let mut parts = cmd_str.split_whitespace();
    let program = parts.next().unwrap_or("sh");
    let args: Vec<&str> = parts.collect();
    
    let mut child = Command::new(program)
        .args(&args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    
    // Stream stdout
    let mut reader = BufReader::new(stdout).lines();
    while let Some(line) = reader.next_line().await? {
        let frame = format!("{{\"session\":\"{}\",\"stream\":\"stdout\",\"data\":\"{}\"}}", session_id, line.replace('"', "\\\""));
        send_frame(frame.as_bytes());
    }
    
    // Stream stderr
    let mut reader = BufReader::new(stderr).lines();
    while let Some(line) = reader.next_line().await? {
        let frame = format!("{{\"session\":\"{}\",\"stream\":\"stderr\",\"data\":\"{}\"}}", session_id, line.replace('"', "\\\""));
        send_frame(frame.as_bytes());
    }
    
    let status = child.wait().await?;
    let frame = format!("{{\"session\":\"{}\",\"stream\":\"exit\",\"code\":{}}}", session_id, status.code().unwrap_or(-1));
    send_frame(frame.as_bytes());
    
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
    name: b"shell\0".as_ptr() as *const c_char,
    version: 2,
    init: shadow_module_init,
    handle_cmd: shadow_module_handle_cmd,
    shutdown: shadow_module_shutdown,
};

#[no_mangle]
pub unsafe extern "C" fn shadow_module_get_api() -> *const ShadowModuleApi {
    &SHADOW_API as *const ShadowModuleApi
}