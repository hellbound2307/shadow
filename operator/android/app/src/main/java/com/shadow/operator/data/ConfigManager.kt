package com.shadow.operator.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    companion object {
        private const val KEY_CONFIG = "client_config"
        private const val KEY_IMPLANTS = "cached_implants"
    }

    suspend fun getConfig(): ClientConfig = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_CONFIG, null)
        if (json.isNullOrBlank()) return@withContext ClientConfig()
        try { gson.fromJson(json, ClientConfig::class.java) ?: ClientConfig() } catch (e: Exception) { ClientConfig() }
    }

    suspend fun saveConfig(config: ClientConfig) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_CONFIG, gson.toJson(config)).apply()
    }

    suspend fun updateWorkerUrl(url: String) = withContext(Dispatchers.IO) {
        val config = getConfig().copy(workerUrl = url)
        saveConfig(config)
    }

    suspend fun cacheImplants(implants: List<Implant>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_IMPLANTS, gson.toJson(implants)).apply()
    }

    suspend fun getCachedImplants(): List<Implant> = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_IMPLANTS, "") ?: return@withContext emptyList()
        try { gson.fromJson(json, object : TypeToken<List<Implant>>() {}.type) } catch (e: Exception) { emptyList() }
    }
}
