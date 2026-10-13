package com.shadow

import android.app.Application
import android.content.Context
import android.os.Looper
import android.util.Log

/**
 * Application entry point. Holds the app Context so both the Kotlin JNI bridge
 * (ShadowBridge) and the Rust core can reach system services without threading
 * a Context through every call site.
 */
class ShadowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        installCrashGuard()
    }

    /**
     * Keep the process alive when background work throws.
     *
     * One uncaught exception on any thread tears down the whole process, and when
     * the process holding the bound accessibility service dies, Android records
     * that service as crashed and stops binding it -- leaving the implant dead
     * until someone toggles Accessibility off and on by hand. That is exactly how
     * a JNI class-lookup failure managed to disable accessibility.
     *
     * Background threads (JNI callbacks, tokio workers named "Thread-N") therefore
     * log and live; the main thread keeps the platform handler so genuine startup
     * failures still surface instead of hanging silently.
     */
    private fun installCrashGuard() {
        val mainThread = Looper.getMainLooper().thread
        val mainHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread === mainThread) {
                mainHandler?.uncaughtException(thread, error)
            } else {
                Log.e(TAG, "Uncaught exception on ${thread.name}; process kept alive", error)
            }
        }
    }

    companion object {
        private const val TAG = "ShadowApp"

        @Volatile
        var appContext: Context? = null
            private set
    }
}