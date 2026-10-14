package com.shadow

import android.accounts.AbstractAccountAuthenticator
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Bound service that hands AccountManager our stub authenticator. Like the sync
 * service, its only job is discoverability — AccountManager binds here to obtain
 * the authenticator for ACCOUNT_TYPE. No UI, no notification.
 */
class ShadowAuthenticatorService : Service() {

    private var authenticator: AbstractAccountAuthenticator? = null

    override fun onCreate() {
        super.onCreate()
        authenticator = ShadowAuthenticator(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return authenticator?.iBinder
    }
}