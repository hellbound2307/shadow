package com.shadow

import android.app.Application
import android.content.Context

/**
 * Application entry point. Holds the app Context so both the Kotlin JNI bridge
 * (ShadowBridge) and the Rust core can reach system services without threading
 * a Context through every call site.
 */
class ShadowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        @Volatile
        var appContext: Context? = null
            private set
    }
}