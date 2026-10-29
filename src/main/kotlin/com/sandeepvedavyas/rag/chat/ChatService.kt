package com.sandeepvedavyas.rag.chat

import com.sandeepvedavyas.rag.config.RagProperties
import com.sandeepvedavyas.rag.domain.Rejection
import com.sandeepvedavyas.rag.domain.RequestRejectedException
import com.sandeepvedavyas.rag.llm.ChatModel
import com.sandeepvedavyas.rag.llm.EmbeddingModel
import com.sandeepvedavyas.rag.retrieval.ScoredChunk
import com.sandeepvedavyas.rag.retrieval.VectorStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlin.math.round
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

data class Citation(
    val index: Int,
    val documentId: String,
    val chunkId: String,
    val title: String,
    val sourceName: String,
    val score: Double,
    val text: String,
)

data class ChatAnswer(
    val answer: String,
    val model: String?,
    val citations: List<Citation>,
)

@Service
class ChatService(
    private val embeddingModel: EmbeddingModel,
    private val chatModel: ChatModel,
    private val vectorStore: VectorStore,
    private val properties: RagProperties,
    private val meterRegistry: MeterRegistry,
) {
    fun answer(question: String, topK: Int?): Mono<ChatAnswer> = Mono.defer { answerNow(question, topK) }

    private fun answerNow(question: String, topK: Int?): Mono<ChatAnswer> {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || trimmed.length > 4_000) {
            return Mono.error(RequestRejectedException(Rejection.BAD_REQUEST, "Question must be 1 to 4000 characters"))
        }
        val limit = topK ?: properties.retrieval.topK
        if (limit !in 1..20) {
            return Mono.error(RequestRejectedException(Rejection.BAD_REQUEST, "topK must be between 1 and 20"))
        }
        val sample = Timer.start(meterRegistry)
        return embeddingModel.embed(listOf(trimmed))
            .flatMap { vectors ->
                val query = vectors.firstOrNull()
                    ?: return@flatMap Mono.error(IllegalStateException("Embedder returned no vector"))
                val hits = vectorStore.search(query, limit)
                    .filter { it.score >= properties.retrieval.minScore }
                if (hits.isEmpty()) {
                    meterRegistry.counter("rag.chat.empty_context").increment()
                    log.info("chat abstained questionChars={}", trimmed.length)
                    Mono.just(ChatAnswer(NO_CONTEXT_ANSWER, model = null, citations = emptyList()))
                } else {
                    val user = PromptBuilder.userMessage(trimmed, hits)
                    chatModel.complete(PromptBuilder.SYSTEM, user).map { completion ->
                        log.info(
                            "chat answered citations={} model={} questionChars={}",
                            hits.size,
                            completion.model,
                            trimmed.length,
                        )
                        ChatAnswer(
                            answer = completion.content.trim(),
                            model = completion.model,
                            citations = hits.mapIndexed { index, hit -> hit.toCitation(index + 1) },
                        )
                    }
                }
            }
            .doOnSuccess { answer ->
                val outcome = if (answer.model == null) "empty" else "success"
                sample.stop(meterRegistry.timer("rag.chat", "outcome", outcome))
            }
            .doOnError {
                sample.stop(meterRegistry.timer("rag.chat", "outcome", "error"))
            }
    }

    private fun ScoredChunk.toCitation(index: Int) = Citation(
        index = index,
        documentId = chunk.documentId,
        chunkId = chunk.id,
        title = chunk.title,
        sourceName = chunk.sourceName,
        score = round(score * 10_000.0) / 10_000.0,
        text = chunk.text,
    )

    companion object {
        const val NO_CONTEXT_ANSWER: String = "I don't have enough indexed context to answer that."
        private val log = LoggerFactory.getLogger(ChatService::class.java)
    }
}
