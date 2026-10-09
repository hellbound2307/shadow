package com.shadow.operator.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ImplantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(implant: ImplantEntity): Long

    @Update
    suspend fun update(implant: ImplantEntity): Int

    @Query("DELETE FROM implants WHERE implant_id = :implantId")
    suspend fun delete(implantId: String): Int

    @Query("SELECT * FROM implants WHERE implant_id = :implantId")
    suspend fun getById(implantId: String): ImplantEntity?

    @Query("SELECT * FROM implants ORDER BY last_seen DESC")
    fun getAll(): Flow<List<ImplantEntity>>

    @Query("SELECT * FROM implants WHERE is_online = 1 ORDER BY last_seen DESC")
    fun getOnline(): Flow<List<ImplantEntity>>

    @Query("SELECT * FROM implants WHERE implant_id IN (:implantIds)")
    suspend fun getByIds(implantIds: List<String>): List<ImplantEntity>

    @Query("UPDATE implants SET is_online = :online, last_seen = :now WHERE implant_id = :implantId")
    suspend fun setOnlineStatus(implantId: String, online: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE implants SET battery_level = :level, network_type = :netType, network_ip = :ip WHERE implant_id = :implantId")
    suspend fun updateSystemInfo(implantId: String, level: Int, netType: String?, ip: String?)

    @Query("UPDATE implants SET installed_modules = :modules WHERE implant_id = :implantId")
    suspend fun updateModules(implantId: String, modules: String)

    @Query("UPDATE implants SET available_modules = :modules WHERE implant_id = :implantId")
    suspend fun updateAvailableModules(implantId: String, modules: String)
}

@Dao
interface CommandHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(history: CommandHistoryEntity): Long

    @Query("SELECT * FROM command_history WHERE implant_id = :implantId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getHistory(implantId: String, limit: Int = 100, offset: Int = 0): List<CommandHistoryEntity>

    @Query("SELECT * FROM command_history WHERE id = :id")
    suspend fun getById(id: Long): CommandHistoryEntity?

    @Query("UPDATE command_history SET status = :status, response_payload = :response, duration_ms = :duration WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, response: String?, duration: Long)

    @Query("DELETE FROM command_history WHERE timestamp < :cutoff")
    suspend fun cleanupOld(cutoff: Long): Int
}

@Dao
interface ModuleRegistryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(module: ModuleRegistryEntity): Long

    @Query("SELECT * FROM module_registry WHERE module_name = :name")
    suspend fun getByName(name: String): ModuleRegistryEntity?

    @Query("SELECT * FROM module_registry ORDER BY module_name")
    fun getAll(): Flow<List<ModuleRegistryEntity>>

    @Query("UPDATE module_registry SET is_loaded = :loaded WHERE module_name = :name")
    suspend fun setLoaded(name: String, loaded: Boolean): Int

    @Query("DELETE FROM module_registry WHERE module_name = :name")
    suspend fun delete(name: String): Int
}

@Dao
interface MediaRecordingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(recording: MediaRecordingEntity): Long

    @Query("SELECT * FROM media_recordings WHERE implant_id = :implantId ORDER BY timestamp DESC")
    suspend fun getByImplant(implantId: String): List<MediaRecordingEntity>

    @Query("SELECT * FROM media_recordings WHERE media_type = :type AND implant_id = :implantId ORDER BY timestamp DESC")
    suspend fun getByTypeAndImplant(type: String, implantId: String): List<MediaRecordingEntity>

    @Query("DELETE FROM media_recordings WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Database(
    entities = [
        ImplantEntity::class,
        CommandHistoryEntity::class,
        ModuleRegistryEntity::class,
        MediaRecordingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun implantDao(): ImplantDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun moduleRegistryDao(): ModuleRegistryDao
    abstract fun mediaRecordingDao(): MediaRecordingDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shadow_operator.db"
                )
                    .setJournalMode(JournalMode.WAL)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}