package com.shadow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Cross-process mutual resurrection.
 *
 * Two processes — the default (UI/a11y) process and the ":sync" process — bind to
 * each other. Android cannot reap a process that another process is bound to, and a
 * process that dies fires onServiceDisconnected in the survivor, which immediately
 * rebinds it. The reaper must kill BOTH simultaneously to win; it reaps one at a
 * time.
 *
 * We use bindService (not startService) because binds are exempt from the Android 8+
 * background-start restrictions and because an active binding is what raises the
 * process tier.
 *
 * Anti-thrash: every rebind is scheduled with exponential backoff and a hard cap.
 * An unbounded resurrection loop is the #1 way to get flagged by ColorOS's battery
 * manager and reaped harder — so after MAX_ATTEMPTS we stop retrying and wait for an
 * external wake (sync/alarm/boot) instead of spinning.
 */
object DualProcess {

    private const val TAG = "ShadowDual"

    /** Max consecutive failed rebinds before we stand down and wait for a system wake. */
    private const val MAX_ATTEMPTS = 5

    /** Base backoff, doubled each attempt: 1s, 2s, 4s, 8s, 16s. */
    private const val BASE_BACKOFF_MS = 1000L

    // ---- main process side ------------------------------------------------

    @Volatile
    private var beaconBinder: IBinder? = null
    @Volatile
    private var attempts = 0

    private val beaconConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            beaconBinder = service
            attempts = 0 // healthy again
            Log.i(TAG, "beacon bound (main->:sync)")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The :sync process was reaped. Bring it straight back.
            beaconBinder = null
            Log.w(TAG, "beacon disconnected — rebinding")
            scheduleRebind()
        }
    }

    /** Ensure the :sync beacon process is up and mutually bound. Idempotent. */
    fun wakeBeacon(ctx: Context) {
        try {
            // Explicit-component bind into the :sync process.
            val intent = Intent(ctx, BeaconService::class.java)
            ctx.bindService(intent, beaconConn, Context.BIND_AUTO_CREATE)
        } catch (e: Throwable) {
            Log.w(TAG, "wakeBeacon failed: ${e.message}")
        }
    }

    private fun scheduleRebind() {
        attempts++
        if (attempts > MAX_ATTEMPTS) {
            Log.w(TAG, "rebind cap reached ($MAX_ATTEMPTS) — standing down until next system wake")
            attempts = 0
            return
        }
        val delay = BASE_BACKOFF_MS shl (attempts - 1) // 1s,2s,4s,8s,16s
        Log.i(TAG, "rebind attempt $attempts in ${delay}ms")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Use the app context captured at first wake; the process is alive or we
            // wouldn't be running this handler.
            pendingCtx?.let { wakeBeacon(it) }
        }, delay)
    }

    @Volatile
    private var pendingCtx: Context? = null

    fun remember(ctx: Context) { pendingCtx = ctx.applicationContext }

    // ---- IPC (via SyncProvider.call, never SharedPreferences) --------------

    /**
     * Cross-process ping through the provider. SharedPreferences is NOT
     * multi-process safe (each process caches its own copy, concurrent writes
     * clobber), so all coordination goes through the ContentProvider.
     */
    fun pingProvider(ctx: Context, tag: String): Boolean {
        return try {
            val uri = android.net.Uri.parse("content://${ShadowAuthenticator.AUTHORITY}")
            ctx.contentResolver.call(uri, "ping", tag, null)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "pingProvider failed: ${e.message}")
            false
        }
    }
}