package com.example.locationtracker

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SecurityManager {
    fun signRequest(deviceId: String, timestamp: Long, nonce: String, body: ByteArray, secretKeyBase64: String): String {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val signingString = "$deviceId\n$timestamp\n$nonce\n$bodyHash"
        val secretKey = secretKeyBase64.toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
        val signature = mac.doFinal(signingString.toByteArray())
        return signature.joinToString("") { "%02x".format(it) }
    }

    fun generateNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
