package com.sandeepvedavyas.rag.auth

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * Single shared bearer credential, compared by SHA-256 digest so a length
 * mismatch does not return early. This is a gateway-style service key, not a user directory.
 */
class ApiKeyVerifier(
    expected: String,
) {
    private val expectedDigest: ByteArray? = expected.takeIf { it.isNotBlank() }?.let(::digest)

    fun matches(authorizationHeader: String?): Boolean {
        val expected = expectedDigest ?: return false
        val token = bearerToken(authorizationHeader) ?: return false
        return MessageDigest.isEqual(digest(token), expected)
    }

    companion object {
        fun bearerToken(authorizationHeader: String?): String? {
            if (authorizationHeader.isNullOrBlank()) return null
            val parts = authorizationHeader.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
            val token = parts[1].trim()
            if (token.isEmpty() || token.any { it.isWhitespace() }) return null
            return token
        }

        private fun digest(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    }
}
