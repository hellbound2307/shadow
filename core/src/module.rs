use anyhow::{anyhow, Result};
use libloading::{Library, Symbol};
use std::collections::HashMap;
use std::ffi::CStr;
use std::os::raw::c_char;
use std::path::PathBuf;
use tracing::info;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ShadowContext {
    // Callbacks passed to the loaded module so it can interact with the core
    pub send_frame: extern "C" fn(*const u8, usize) -> i32,
    pub log_info: extern "C" fn(*const c_char),
}

// Safety: ShadowContext only contains fn pointers, which are Send/Sync.
unsafe impl Send for ShadowContext {}
unsafe impl Sync for ShadowContext {}

#[repr(C)]
pub struct ShadowModuleApi {
    pub name: *const c_char,
    pub version: u32,
    pub init: extern "C" fn(ShadowContext) -> i32,
    pub handle_cmd: extern "C" fn(*const u8, usize) -> *mut u8,
    pub shutdown: extern "C" fn() -> i32,
}

// Safety: ShadowModuleApi is loaded via libloading and stays in-process.
// The raw pointers are only valid within the loaded library's lifetime,
// which we own. Marking Send is safe because the library is never
// unloaded while the registry holds a reference.
unsafe impl Send for ShadowModuleApi {}
unsafe impl Sync for ShadowModuleApi {}

pub struct LoadedModule {
    lib: Library,
    api: ShadowModuleApi,
    pub name: String,
}

pub struct ModuleRegistry {
    loaded: HashMap<String, LoadedModule>,
    module_dir: PathBuf,
}

impl ModuleRegistry {
    pub fn new(module_dir: PathBuf) -> Self {
        Self {
            loaded: HashMap::new(),
            module_dir,
        }
    }

    pub unsafe fn load_from_file(&mut self, path: PathBuf, context: ShadowContext) -> Result<()> {
        info!("Loading module from {:?}", path);
        let lib = Library::new(&path)?;

        let get_api: Symbol<unsafe extern "C" fn() -> *const ShadowModuleApi> =
            lib.get(b"shadow_module_get_api")?;
        let api_ptr = get_api();
        if api_ptr.is_null() {
            return Err(anyhow!("Module returned null API pointer"));
        }

        let api = std::ptr::read(api_ptr);
        let name = CStr::from_ptr(api.name).to_string_lossy().into_owned();

        info!("Initializing module: {} v{}", name, api.version);
        let res = (api.init)(context);
        if res != 0 {
            return Err(anyhow!("Module {} init failed with code {}", name, res));
        }

        self.loaded
            .insert(name.clone(), LoadedModule { lib, api, name });
        Ok(())
    }

    pub fn handle_command(&self, module: &str, payload: &[u8]) -> Result<Vec<u8>> {
        if let Some(loaded) = self.loaded.get(module) {
            unsafe {
                let res_ptr = (loaded.api.handle_cmd)(payload.as_ptr(), payload.len());
                if res_ptr.is_null() {
                    return Ok(Vec::new());
                }
                // Module returns a null-terminated C string
                let c_str = CStr::from_ptr(res_ptr as *const c_char);
                let bytes = c_str.to_bytes().to_vec();
                // TODO: Module needs to expose a free() function to prevent memory leaks
                Ok(bytes)
            }
        } else {
            Err(anyhow!("Module {} not loaded", module))
        }
    }
}
