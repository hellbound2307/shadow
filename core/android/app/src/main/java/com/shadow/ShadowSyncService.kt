package com.shadow

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Bound service SyncManager uses to obtain the sync adapter. This service is
 * intentionally trivial — its existence is what lets the system discover the
 * adapter for our account. It is a *bound* service (no startForeground, no
 * notification), bound by the system itself.
 */
class ShadowSyncService : Service() {

    private var adapter: ShadowSyncAdapter? = null

    override fun onCreate() {
        super.onCreate()
        adapter = ShadowSyncAdapter(applicationContext, true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return adapter?.syncAdapterBinder
    }
}