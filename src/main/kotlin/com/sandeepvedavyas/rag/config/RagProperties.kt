package com.sandeepvedavyas.rag.config

import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "rag")
data class RagProperties(
    val auth: Auth = Auth(),
    val ingest: Ingest = Ingest(),
    val retrieval: Retrieval = Retrieval(),
    val llm: Llm = Llm(),
) {
    data class Auth(
        val apiKey: String = LOCAL_DEV_API_KEY,
    )

    data class Ingest(
        val maxBytes: Int = 1_048_576,
        val chunkSize: Int = 800,
        val chunkOverlap: Int = 120,
        val embeddingBatchSize: Int = 16,
    )

    data class Retrieval(
        val topK: Int = 4,
        val minScore: Double = 0.2,
    )

    data class Llm(
        val openaiBaseUrl: String = "http://localhost:11434/v1",
        val openaiApiKey: String = "",
        val gatewayBaseUrl: String = "",
        val gatewayApiKey: String = "",
        val chatModel: String = "llama3.2",
        val embeddingModel: String = "nomic-embed-text",
        val timeout: Duration = Duration.ofSeconds(30),
        val maxRetries: Int = 2,
        val temperature: Double = 0.1,
    ) {
        fun resolvedBaseUrl(): String = gatewayBaseUrl.ifBlank { openaiBaseUrl }.trim().trimEnd('/')

        fun baseUrl(): String = resolvedBaseUrl() + "/"

        /** Gateway URL wins as a pair: a set URL never falls through to the OpenAI key. */
        fun apiKey(): String = if (gatewayBaseUrl.isNotBlank()) gatewayApiKey.trim() else openaiApiKey.trim()

        fun usesGateway(): Boolean = gatewayBaseUrl.isNotBlank()

        fun displayBaseUrl(): String {
            val raw = resolvedBaseUrl()
            return try {
                val uri = URI(raw)
                if (uri.host.isNullOrBlank()) raw
                else URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            } catch (_: Exception) {
                "(invalid)"
            }
        }
    }

    init {
        require(ingest.maxBytes in 1..10_000_000) { "rag.ingest.max-bytes must be between 1 and 10000000" }
        require(ingest.chunkSize >= 100) { "rag.ingest.chunk-size must be at least 100" }
        require(ingest.chunkOverlap in 0 until ingest.chunkSize) {
            "rag.ingest.chunk-overlap must be >= 0 and < chunk size"
        }
        require(ingest.embeddingBatchSize in 1..128) { "rag.ingest.embedding-batch-size must be between 1 and 128" }
        require(retrieval.topK in 1..20) { "rag.retrieval.top-k must be between 1 and 20" }
        require(retrieval.minScore in -1.0..1.0) { "rag.retrieval.min-score must be between -1 and 1" }
        require(llm.chatModel.isNotBlank()) { "chat model must be set" }
        require(llm.embeddingModel.isNotBlank()) { "embedding model must be set" }
        require(llm.timeout.seconds in 1..120) { "rag.llm.timeout must be between 1s and 120s" }
        require(llm.maxRetries in 0..5) { "rag.llm.max-retries must be between 0 and 5" }
        require(llm.temperature in 0.0..2.0) { "rag.llm.temperature must be between 0 and 2" }
        val base = llm.resolvedBaseUrl()
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "LLM base URL must start with http:// or https://"
        }
    }

    companion object {
        const val LOCAL_DEV_API_KEY = "local-dev-key"
    }
}
