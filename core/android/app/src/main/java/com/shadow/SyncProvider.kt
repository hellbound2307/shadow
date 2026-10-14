package com.shadow

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

class SyncProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Enqueue the worker when the provider is initialized.
        // ContentProviders initialize in manifest order BEFORE androidx.startup's
        // InitializationProvider, so WorkManager isn't ready yet at this point.
        // Posting to the main looper defers the enqueue until ALL providers
        // (including WorkManager's default initializer) have been installed.
        context?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    ShadowWorker.enqueue(ctx)
                    // Promote to foreground service the moment the provider spins up,
                    // which is the fastest recovery path after the process is killed.
                    // The core is started inside the service's onStartCommand.
                    SyncForegroundService.start(ctx)
                } catch (e: Exception) {
                    android.util.Log.w("SyncProvider", "enqueue deferred failed", e)
                }
            }
        }
        return true
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