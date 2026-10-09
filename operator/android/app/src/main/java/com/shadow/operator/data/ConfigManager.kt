package com.shadow.operator.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConfigManager(private val context: Context) {
    private val gson = Gson()
    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "shadow_config",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private const val KEY_CONFIG = "client_config"
    private const val KEY_IMPLANTS = "cached_implants"
    private const val KEY_SESSION = "active_session"

    suspend fun getConfig(): ClientConfig = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_CONFIG, "") ?: return@withContext ClientConfig()
        try { gson.fromJson(json, ClientConfig::class.java) } catch (e: Exception) { ClientConfig() }
    }

    suspend fun saveConfig(config: ClientConfig) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_CONFIG, gson.toJson(config)).apply()
    }

    suspend fun updateWorkerUrl(url: String) = withContext(Dispatchers.IO) {
        val config = getConfig().copy(workerUrl = url)
        saveConfig(config)
    }

    // Cache implants for offline viewing
    suspend fun cacheImplants(implants: List<Implant>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_IMPLANTS, gson.toJson(implants)).apply()
    }

    suspend fun getCachedImplants(): List<Implant> = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_IMPLANTS, "") ?: return@withContext emptyList()
        try { gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<Implant>>() {}.type) } catch (e: Exception) { emptyList() }
    }
}

data class ClientConfig(
    var workerUrl: String = "wss://shadow-worker.marwannaili-23-07.workers.dev",
    var operatorPubKey: String = "",
    var operatorPrivKey: String = "", // Ed25519 private key (hex) for signing RPC commands
    var moduleSigningPubKey: String = "",
    var transportPubKey: String = "",
    var autoReconnect: Boolean = true,
    var reconnectIntervalSec: Int = 30,
    var maxReconnectAttempts: Int = -1,
    var useTor: Boolean = false,
    var torProxyHost: String = "127.0.0.1",
    var torProxyPort: Int = 9050
)