package com.shadow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("ShadowBoot", "boot event: $action")
            // Re-register the account + periodic sync. If the account survived, this
            // is a no-op; if it was removed, this re-seeds the system-spawned path.
            try { AccountRegistrar.ensure(context) } catch (e: Exception) {
                Log.w("ShadowBoot", "account ensure failed: ${e.message}")
            }
            // Bring up the cross-process beacon host.
            try {
                DualProcess.remember(context)
                DualProcess.wakeBeacon(context)
            } catch (e: Exception) {
                Log.w("ShadowBoot", "beacon wake failed: ${e.message}")
            }
            // WorkManager periodic reviver.
            ShadowWorker.enqueue(context)
        }
    }
}