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
            Log.i("ShadowBoot", "Device booted. Restarting ShadowWorker...")
            ShadowWorker.enqueue(context)
            // Give a11y time to bind (AMS does this on its own schedule, usually
            // within a second or two of boot) before deciding whether the
            // foreground fallback is needed.
            com.shadow.KeepAlive.schedule(context, 20_000L, "boot")
        }
    }
}