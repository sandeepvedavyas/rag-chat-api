package com.sandeepvedavyas.rag.chat

import com.sandeepvedavyas.rag.config.RagProperties
import com.sandeepvedavyas.rag.retrieval.DocumentRecord
import com.sandeepvedavyas.rag.retrieval.InMemoryVectorStore
import com.sandeepvedavyas.rag.retrieval.StoredChunk
import com.sandeepvedavyas.rag.support.BagOfWords
import com.sandeepvedavyas.rag.support.FakeChatModel
import com.sandeepvedavyas.rag.support.HashEmbeddingModel
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class ChatServiceTest {
    private val store = InMemoryVectorStore()
    private val chatModel = FakeChatModel()
    private val service = ChatService(
        embeddingModel = HashEmbeddingModel(),
        chatModel = chatModel,
        vectorStore = store,
        properties = RagProperties(),
        meterRegistry = SimpleMeterRegistry(),
    )

    @Test
    fun `does not call the chat model when the index has no matching chunk`() {
        StepVerifier.create(service.answer("How is quantum chromodynamics lattice spacing measured?", null))
            .assertNext { answer ->
                assertThat(answer.answer).isEqualTo(ChatService.NO_CONTEXT_ANSWER)
                assertThat(answer.model).isNull()
                assertThat(answer.citations).isEmpty()
            }
            .verifyComplete()
        assertThat(chatModel.prompts).isEmpty()
    }

    @Test
    fun `grounds an answer on the matching chunk and numbers it`() {
        val text = "The parcel-lock protocol cancels child coroutines when the parent scope is cancelled."
        store.insert(
            DocumentRecord(
                id = "doc-1",
                title = "Parcel lock",
                sourceName = "notes.md",
                contentHash = "sha256:test",
                createdAt = Instant.EPOCH,
                chunkCount = 1,
            ),
            listOf(
                StoredChunk(
                    id = "doc-1:0",
                    documentId = "doc-1",
                    title = "Parcel lock",
                    sourceName = "notes.md",
                    ordinal = 0,
                    text = text,
                    vector = BagOfWords.embed(text),
                ),
            ),
        )

        StepVerifier.create(
            service.answer(
                "What does the parcel-lock protocol do to child coroutines when the parent scope is cancelled?",
                topK = 2,
            ),
        )
            .assertNext { answer ->
                assertThat(answer.model).isEqualTo("fake-chat")
                assertThat(answer.citations).hasSize(1)
                assertThat(answer.citations[0].index).isEqualTo(1)
                assertThat(answer.citations[0].text).contains("parcel-lock")
            }
            .verifyComplete()
        assertThat(chatModel.prompts).hasSize(1)
        assertThat(chatModel.prompts[0]).contains("[1] Parcel lock (notes.md)")
        assertThat(chatModel.prompts[0]).contains(text)
    }
}
