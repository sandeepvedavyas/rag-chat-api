package com.sandeepvedavyas.rag.ingest

import com.sandeepvedavyas.rag.config.RagProperties
import com.sandeepvedavyas.rag.domain.RequestRejectedException
import com.sandeepvedavyas.rag.retrieval.InMemoryVectorStore
import com.sandeepvedavyas.rag.support.HashEmbeddingModel
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class IngestServiceTest {
    private val embedder = HashEmbeddingModel()
    private val store = InMemoryVectorStore()
    private val service = IngestService(
        chunker = TextChunker(200, 40),
        embeddingModel = embedder,
        vectorStore = store,
        properties = RagProperties(ingest = RagProperties.Ingest(chunkSize = 200, chunkOverlap = 40)),
        meterRegistry = SimpleMeterRegistry(),
        clock = Clock.systemUTC(),
    )

    @Test
    fun `a repeated body does not embed again`() {
        val command = IngestCommand(
            title = "Notes",
            content = "Structured concurrency cancels child coroutines with the parent scope.",
            sourceName = "notes.md",
        )

        val first = service.ingest(command)
        val second = service.ingest(command.copy(title = "Renamed"))

        StepVerifier.create(first)
            .assertNext { ingested ->
                assertThat(ingested.created).isTrue()
                assertThat(ingested.chunkCount).isEqualTo(1)
            }
            .verifyComplete()
        StepVerifier.create(second)
            .assertNext { ingested ->
                assertThat(ingested.created).isFalse()
                assertThat(ingested.title).isEqualTo("Notes")
            }
            .verifyComplete()
        assertThat(embedder.calls.get()).isEqualTo(1)
        assertThat(store.countDocuments()).isEqualTo(1)
    }

    @Test
    fun `oversized text is rejected before an embedding call`() {
        val counting = HashEmbeddingModel()
        val limited = IngestService(
            chunker = TextChunker(200, 40),
            embeddingModel = counting,
            vectorStore = InMemoryVectorStore(),
            properties = RagProperties(ingest = RagProperties.Ingest(chunkSize = 200, chunkOverlap = 40, maxBytes = 32)),
            meterRegistry = SimpleMeterRegistry(),
            clock = Clock.systemUTC(),
        )
        StepVerifier.create(limited.ingest(IngestCommand("Notes", "x".repeat(80), "notes.md")))
            .expectError(RequestRejectedException::class.java)
            .verify()
        assertThat(counting.calls.get()).isEqualTo(0)
    }
}
