package com.sandeepvedavyas.rag.retrieval

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.springframework.stereotype.Component

@Component
class InMemoryVectorStore : VectorStore {
    private val lock = ReentrantReadWriteLock()
    private val documents = LinkedHashMap<String, DocumentRecord>()
    private val chunksById = LinkedHashMap<String, StoredChunk>()
    private val chunkIdsByDocument = LinkedHashMap<String, List<String>>()
    private val documentIdByHash = HashMap<String, String>()
    private var dimensions: Int? = null

    override fun findByHash(contentHash: String): DocumentRecord? = lock.read {
        documentIdByHash[contentHash]?.let { documents[it] }
    }

    override fun findById(id: String): DocumentRecord? = lock.read { documents[id] }

    override fun listDocuments(): List<DocumentRecord> = lock.read { documents.values.toList() }

    override fun countDocuments(): Int = lock.read { documents.size }

    override fun countChunks(): Int = lock.read { chunksById.size }

    override fun insert(document: DocumentRecord, chunks: List<StoredChunk>): InsertResult = lock.write {
        documentIdByHash[document.contentHash]?.let { existingId ->
            return@write InsertResult(documents.getValue(existingId), created = false)
        }
        if (chunks.isEmpty()) {
            throw IllegalArgumentException("Refusing to index a document with no chunks")
        }
        if (document.chunkCount != chunks.size) {
            throw IllegalArgumentException("chunkCount does not match the chunks being stored")
        }
        val distinctDimensions = chunks.map { it.vector.size }.distinct()
        if (distinctDimensions.size != 1 || distinctDimensions.first() == 0) {
            throw VectorDimensionException("Embedding batch has mixed or empty dimensions: $distinctDimensions")
        }
        val dimension = distinctDimensions.first()
        val indexedDimension = dimensions
        if (indexedDimension != null && indexedDimension != dimension && chunksById.isNotEmpty()) {
            throw VectorDimensionException(
                "Index dimension is $indexedDimension but embeddings are $dimension. Delete the index or keep one embedding model.",
            )
        }
        val stored = chunks.map { chunk ->
            StoredChunk(
                id = chunk.id,
                documentId = chunk.documentId,
                title = chunk.title,
                sourceName = chunk.sourceName,
                ordinal = chunk.ordinal,
                text = chunk.text,
                vector = Vectors.normalize(chunk.vector),
            )
        }
        documents[document.id] = document
        chunkIdsByDocument[document.id] = stored.map { it.id }
        stored.forEach { chunksById[it.id] = it }
        documentIdByHash[document.contentHash] = document.id
        dimensions = dimension
        InsertResult(document, created = true)
    }

    override fun delete(documentId: String): Boolean = lock.write {
        val removed = documents.remove(documentId) ?: return@write false
        chunkIdsByDocument.remove(documentId).orEmpty().forEach { chunksById.remove(it) }
        documentIdByHash.remove(removed.contentHash)
        if (chunksById.isEmpty()) {
            dimensions = null
        }
        true
    }

    override fun search(query: FloatArray, topK: Int): List<ScoredChunk> = lock.read {
        if (topK <= 0 || chunksById.isEmpty()) {
            return@read emptyList()
        }
        val indexedDimension = dimensions
        if (indexedDimension != null && query.size != indexedDimension) {
            throw VectorDimensionException(
                "Query dimension ${query.size} does not match index dimension $indexedDimension",
            )
        }
        val normalized = Vectors.normalize(query)
        chunksById.values
            .asSequence()
            .map { chunk -> ScoredChunk(chunk, Vectors.dot(normalized, chunk.vector)) }
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunk.ordinal }.thenBy { it.chunk.id })
            .take(topK)
            .toList()
    }
}
