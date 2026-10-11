package com.shadow.operator.data

import android.util.Log
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Signs operator RPC commands with Ed25519.
 * The Worker verifies signatures against the operator's public key.
 */
object Ed25519Signer {
    private const val TAG = "Ed25519Signer"

    /**
     * Normalise a private key supplied either as:
     *   - 64 hex chars (raw 32-byte seed), or
     *   - a base64 PKCS8 blob ("MC4CAQAwBQYDK2VwBCIEIE..." / BEGIN PRIVATE KEY)
     * Returns the raw 32-byte seed, or null if it cannot be parsed.
     */
    private fun normaliseSeed(key: String): ByteArray? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null

        // Hex form: 64 chars, all hex digits
        if (trimmed.length == 64 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        // Base64 PKCS8 form (possibly with PEM armour)
        val b64 = trimmed
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        return try {
            val der = Base64.getDecoder().decode(b64)
            // PKCS8 Ed25519: the 32-byte seed is the final 32 bytes
            if (der.size < 32) null else der.copyOfRange(der.size - 32, der.size)
        } catch (e: Exception) {
            Log.e(TAG, "Key not hex and not base64 PKCS8", e)
            null
        }
    }

    /**
     * Sign a message using Ed25519.
     * @param privateKeyHex raw 32-byte seed as hex, or base64 PKCS8 blob
     * @param message The message bytes to sign
     * @return Signature as hex string (128 hex chars = 64 bytes)
     */
    fun sign(privateKeyHex: String, message: ByteArray): String? {
        return try {
            val keyBytes = normaliseSeed(privateKeyHex) ?: run {
                Log.e(TAG, "sign: unusable key material")
                return null
            }

            // Build a PKCS8-encoded Ed25519 private key
            // Ed25519 PKCS8 prefix: 302e020100300506032b657004220420
            val pkcs8Prefix = byteArrayOf(
                0x30, 0x2e, 0x02, 0x01, 0x00,
                0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
                0x04, 0x22, 0x04, 0x20
            )
            val pkcs8Key = pkcs8Prefix + keyBytes

            val keySpec = PKCS8EncodedKeySpec(pkcs8Key)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val privateKey: PrivateKey = keyFactory.generatePrivate(keySpec)

            val signer = Signature.getInstance("Ed25519")
            signer.initSign(privateKey)
            signer.update(message)
            val sig = signer.sign()

            // Convert to hex
            sig.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Signing failed", e)
            null
        }
    }

    /**
     * Sign a timestamp + body pair (the format the Worker expects).
     */
    fun signRpc(privateKeyHex: String, timestamp: String, body: String): String? {
        val message = (timestamp + body).toByteArray(Charsets.UTF_8)
        return sign(privateKeyHex, message)
    }
}
