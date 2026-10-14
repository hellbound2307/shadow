package com.shadow

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

class SyncProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // ContentProviders initialize in manifest order BEFORE androidx.startup's
        // InitializationProvider, so WorkManager isn't ready yet. Post to the main
        // looper to defer until all providers are installed.
        context?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    // 1. Register the account + periodic sync — this is the seed of the
                    //    system-spawned wake path. Idempotent.
                    AccountRegistrar.ensure(ctx)
                    // 2. Bring up the cross-process beacon host and mutually bind.
                    DualProcess.remember(ctx)
                    DualProcess.wakeBeacon(ctx)
                    // 3. WorkManager periodic run (belt-and-suspenders reviver).
                    ShadowWorker.enqueue(ctx)
                } catch (e: Exception) {
                    Log.w("SyncProvider", "deferred init failed", e)
                }
            }
        }
        return true
    }

    /**
     * IPC entry point. The dual-process pair coordinates THROUGH THE PROVIDER, not
     * through SharedPreferences — prefs are not multi-process safe (each process
     * caches its own copy and concurrent writes clobber).
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        when (method) {
            "ping" -> {
                out.putBoolean("alive", true)
                out.putString("tag", arg ?: "")
                out.putInt("pid", android.os.Process.myPid())
            }
            else -> out.putBoolean("alive", false)
        }
        return out
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}