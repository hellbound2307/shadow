use shadow_core::module::{ShadowContext, ShadowModuleApi};
use std::os::raw::c_uchar;
use std::ffi::CString;

#[no_mangle]
pub extern "C" fn shadow_module_init(_ctx: ShadowContext) -> i32 {
    0
}

#[no_mangle]
pub extern "C" fn shadow_module_handle_cmd(payload: *const u8, len: usize) -> *mut u8 {
    let req = unsafe { std::slice::from_raw_parts(payload, len) };
    
    // Parse {"action": "click", "view_id": "com.android.settings:id/allow_button"}
    // Return dummy response. In reality, calls back into Kotlin AccessibilityService via JNI.
    let response = "{\"status\":\"click_dispatched\"}";
    
    let c_str = CString::new(response).unwrap();
    c_str.into_raw() as *mut u8
}

#[no_mangle]
pub extern "C" fn shadow_module_shutdown() -> i32 { 0 }

#[no_mangle]
pub static SHADOW_API: ShadowModuleApi = ShadowModuleApi {
    name: b"a11y\0".as_ptr() as *const c_uchar,
    version: 1,
    init: shadow_module_init,
    handle_cmd: shadow_module_handle_cmd,
    shutdown: shadow_module_shutdown,
};

#[no_mangle]
pub unsafe extern "C" fn shadow_module_get_api() -> *const ShadowModuleApi {
    &SHADOW_API as *const ShadowModuleApi
}