package com.sandeepvedavyas.rag.retrieval

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InMemoryVectorStoreTest {
    private val store = InMemoryVectorStore()

    @Test
    fun `ranks by cosine and keeps the higher score first`() {
        val document = document("doc-1", "hash-1", chunkCount = 2)
        store.insert(
            document,
            listOf(
                chunk("a", "doc-1", 0, floatArrayOf(1f, 0f)),
                chunk("b", "doc-1", 1, floatArrayOf(0f, 1f)),
            ),
        )

        val hits = store.search(floatArrayOf(0.9f, 0.1f), topK = 2)

        assertThat(hits.map { it.chunk.id }).containsExactly("a", "b")
        assertThat(hits[0].score).isGreaterThan(hits[1].score)
    }

    @Test
    fun `duplicate content hash returns the original document and does not add chunks`() {
        store.insert(document("doc-1", "hash-1", 1), listOf(chunk("a", "doc-1", 0, floatArrayOf(1f, 0f))))
        val again = store.insert(
            document("doc-2", "hash-1", 1),
            listOf(chunk("b", "doc-2", 0, floatArrayOf(0f, 1f))),
        )

        assertThat(again.created).isFalse()
        assertThat(again.document.id).isEqualTo("doc-1")
        assertThat(store.countChunks()).isEqualTo(1)
        assertThat(store.findByHash("hash-1")?.id).isEqualTo("doc-1")
    }

    @Test
    fun `delete removes chunks and allows a later embedding width`() {
        store.insert(document("doc-1", "hash-1", 1), listOf(chunk("a", "doc-1", 0, floatArrayOf(1f, 0f))))
        assertThat(store.delete("doc-1")).isTrue()
        assertThat(store.search(floatArrayOf(1f, 0f), 4)).isEmpty()
        assertThat(store.delete("doc-1")).isFalse()

        store.insert(document("doc-2", "hash-2", 1), listOf(chunk("c", "doc-2", 0, floatArrayOf(1f, 0f, 0f))))
        assertThat(store.search(floatArrayOf(1f, 0f, 0f), 1)).hasSize(1)
    }

    @Test
    fun `rejects a query whose width does not match the index`() {
        store.insert(document("doc-1", "hash-1", 1), listOf(chunk("a", "doc-1", 0, floatArrayOf(1f, 0f))))

        assertThatThrownBy { store.search(floatArrayOf(1f, 0f, 0f), 1) }
            .isInstanceOf(VectorDimensionException::class.java)
    }

    @Test
    fun `a zero query does not throw and scores nothing`() {
        store.insert(document("doc-1", "hash-1", 1), listOf(chunk("a", "doc-1", 0, floatArrayOf(1f, 0f))))

        val hits = store.search(floatArrayOf(0f, 0f), 2)

        assertThat(hits).hasSize(1)
        assertThat(hits[0].score).isEqualTo(0.0)
    }

    private fun document(id: String, hash: String, chunkCount: Int) = DocumentRecord(
        id = id,
        title = "title",
        sourceName = "notes.md",
        contentHash = hash,
        createdAt = Instant.EPOCH,
        chunkCount = chunkCount,
    )

    private fun chunk(id: String, documentId: String, ordinal: Int, vector: FloatArray) = StoredChunk(
        id = id,
        documentId = documentId,
        title = "title",
        sourceName = "notes.md",
        ordinal = ordinal,
        text = "text-$id",
        vector = vector,
    )
}
