package com.sandeepvedavyas.rag.support

import com.sandeepvedavyas.rag.llm.ChatCompletion
import com.sandeepvedavyas.rag.llm.ChatModel
import com.sandeepvedavyas.rag.llm.EmbeddingModel
import java.util.concurrent.atomic.AtomicInteger
import reactor.core.publisher.Mono

/** Deterministic stand-in used by tests. Production embeddings always come from the HTTP client. */
object BagOfWords {
    const val DIMENSIONS: Int = 256

    fun embed(text: String): FloatArray {
        val vector = FloatArray(DIMENSIONS)
        TOKEN.findAll(text.lowercase()).forEach { match ->
            val token = match.value
            if (token.length > 2) {
                vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1f
            }
        }
        return vector
    }

    private val TOKEN = Regex("[a-z0-9]+")
}

class HashEmbeddingModel : EmbeddingModel {
    val calls: AtomicInteger = AtomicInteger()

    override fun embed(texts: List<String>): Mono<List<FloatArray>> = Mono.fromCallable {
        calls.incrementAndGet()
        texts.map(BagOfWords::embed)
    }
}

class FakeChatModel : ChatModel {
    val prompts: MutableList<String> = mutableListOf()

    override fun complete(system: String, user: String): Mono<ChatCompletion> {
        prompts += user
        return Mono.just(ChatCompletion("answer [1]", "fake-chat"))
    }
}
