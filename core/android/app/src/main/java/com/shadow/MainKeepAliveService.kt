package com.shadow

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * The main-process half of the mutual binding. Held alive by BeaconService (in the
 * ":sync" process) binding to it; in turn it keeps BeaconService alive via
 * DualProcess. Neither needs a notification, a foreground service, or any visible
 * component.
 */
class MainKeepAliveService : Service() {

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): MainKeepAliveService = this@MainKeepAliveService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "main keepalive service up (pid=${android.os.Process.myPid()})")
        DualProcess.remember(applicationContext)
        // Make sure the :sync beacon is up and mutually bound.
        DualProcess.wakeBeacon(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    companion object {
        private const val TAG = "ShadowMain"
    }
}