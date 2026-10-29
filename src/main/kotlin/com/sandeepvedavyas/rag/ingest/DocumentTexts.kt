package com.sandeepvedavyas.rag.ingest

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object DocumentTexts {
    fun normalize(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n').trim()

    fun sha256(normalized: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (ex: CharacterCodingException) {
            throw InvalidUtf8Exception(ex)
        }
    }
}

class InvalidUtf8Exception(cause: Throwable) : RuntimeException("Document must be UTF-8 text", cause)
