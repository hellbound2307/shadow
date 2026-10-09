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
     * Sign a message using Ed25519.
     * @param privateKeyHex Ed25519 private key in hex format (64 hex chars = 32 bytes)
     * @param message The message bytes to sign
     * @return Signature as hex string (128 hex chars = 64 bytes)
     */
    fun sign(privateKeyHex: String, message: ByteArray): String? {
        return try {
            // Parse hex private key
            val keyBytes = privateKeyHex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

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
