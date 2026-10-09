use shadow::module::{ShadowContext, ShadowModuleApi};
use std::os::raw::c_uchar;
use std::ffi::CString;

#[no_mangle]
pub extern "C" fn shadow_module_init(_ctx: ShadowContext) -> i32 {
    0
}

#[no_mangle]
pub extern "C" fn shadow_module_handle_cmd(payload: *const u8, len: usize) -> *mut u8 {
    let _req = unsafe { std::slice::from_raw_parts(payload, len) };
    
    // Parse {"action": "enum_keys"} or {"action": "attest", "challenge": "..."}
    let response = "{\"status\":\"ok\", \"keys\":[\"DeviceKey\", \"IdentityKey\"], \"attestation_supported\": true}";
    let c_str = CString::new(response).unwrap();
    c_str.into_raw() as *mut u8
}

#[no_mangle]
pub extern "C" fn shadow_module_shutdown() -> i32 { 0 }

#[no_mangle]
pub static SHADOW_API: ShadowModuleApi = ShadowModuleApi {
    name: b"crypto\0".as_ptr() as *const c_uchar,
    version: 1,
    init: shadow_module_init,
    handle_cmd: shadow_module_handle_cmd,
    shutdown: shadow_module_shutdown,
};

#[no_mangle]
pub unsafe extern "C" fn shadow_module_get_api() -> *const ShadowModuleApi {
    &SHADOW_API as *const ShadowModuleApi
}
