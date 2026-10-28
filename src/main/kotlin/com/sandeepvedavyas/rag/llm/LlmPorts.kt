package com.sandeepvedavyas.rag.llm

import reactor.core.publisher.Mono

interface EmbeddingModel {
    fun embed(texts: List<String>): Mono<List<FloatArray>>
}

interface ChatModel {
    fun complete(system: String, user: String): Mono<ChatCompletion>
}

data class ChatCompletion(
    val content: String,
    val model: String,
)

class UpstreamLlmException(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    enum class Kind {
        TIMEOUT,
        RATE_LIMIT,
        UNAVAILABLE,
        BAD_RESPONSE,
    }
}
