package com.sandeepvedavyas.rag.retrieval

import java.time.Instant

/**
 * Synchronous on purpose. v1 search is an in-process cosine scan, not remote I/O.
 * A pgvector adapter can implement the same port without changing the API.
 */
interface VectorStore {
    fun findByHash(contentHash: String): DocumentRecord?

    fun findById(id: String): DocumentRecord?

    fun listDocuments(): List<DocumentRecord>

    fun insert(document: DocumentRecord, chunks: List<StoredChunk>): InsertResult

    fun delete(documentId: String): Boolean

    fun search(query: FloatArray, topK: Int): List<ScoredChunk>

    fun countDocuments(): Int

    fun countChunks(): Int
}

data class DocumentRecord(
    val id: String,
    val title: String,
    val sourceName: String,
    val contentHash: String,
    val createdAt: Instant,
    val chunkCount: Int,
)

class StoredChunk(
    val id: String,
    val documentId: String,
    val title: String,
    val sourceName: String,
    val ordinal: Int,
    val text: String,
    vector: FloatArray,
) {
    val vector: FloatArray = vector.copyOf()
}

data class ScoredChunk(
    val chunk: StoredChunk,
    val score: Double,
)

data class InsertResult(
    val document: DocumentRecord,
    val created: Boolean,
)

class VectorDimensionException(message: String) : RuntimeException(message)
