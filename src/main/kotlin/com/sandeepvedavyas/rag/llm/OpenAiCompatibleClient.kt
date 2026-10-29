package com.sandeepvedavyas.rag.llm

import com.sandeepvedavyas.rag.config.RagProperties
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeoutException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry

/**
 * Speaks POST /embeddings and POST /chat/completions on whatever OpenAI-compatible
 * base URL is configured: the sibling gateway, api.openai.com, or Ollama.
 */
class OpenAiCompatibleClient(
    private val webClient: WebClient,
    private val properties: RagProperties,
    private val meterRegistry: MeterRegistry,
) : EmbeddingModel, ChatModel {
    override fun embed(texts: List<String>): Mono<List<FloatArray>> {
        if (texts.isEmpty()) {
            return Mono.just(emptyList())
        }
        return Flux.fromIterable(texts.chunked(properties.ingest.embeddingBatchSize))
            .concatMap { batch -> embedBatch(batch) }
            .collectList()
            .map { batches -> batches.flatten() }
    }

    override fun complete(system: String, user: String): Mono<ChatCompletion> {
        val sample = Timer.start(meterRegistry)
        val body = mapOf(
            "model" to properties.llm.chatModel,
            "temperature" to properties.llm.temperature,
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            ),
        )
        return post("/chat/completions", body, ChatCompletionResponse::class.java)
            .map { response ->
                val content = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
                if (content.isEmpty()) {
                    throw UpstreamLlmException("Upstream chat response was empty", UpstreamLlmException.Kind.BAD_RESPONSE)
                }
                val model = response.model?.takeIf { it.isNotBlank() } ?: properties.llm.chatModel
                response.usage?.let { usage ->
                    log.info(
                        "chat completion model={} promptTokens={} completionTokens={}",
                        model,
                        usage.promptTokens,
                        usage.completionTokens,
                    )
                }
                ChatCompletion(content, model)
            }
            .doOnSuccess { sample.stop(llmTimer("chat", "success")) }
            .doOnError { error ->
                sample.stop(llmTimer("chat", "error"))
                log.warn("chat completion failed: {}", error.message)
            }
    }

    private fun embedBatch(texts: List<String>): Mono<List<FloatArray>> {
        val sample = Timer.start(meterRegistry)
        val body = mapOf(
            "model" to properties.llm.embeddingModel,
            "input" to texts,
        )
        return post("/embeddings", body, EmbeddingResponse::class.java)
            .map { response ->
                val ordered = response.data.sortedBy { it.index }
                val indexes = ordered.map { it.index }
                if (ordered.size != texts.size || indexes != texts.indices.toList()) {
                    throw UpstreamLlmException(
                        "Upstream embeddings response did not cover every input",
                        UpstreamLlmException.Kind.BAD_RESPONSE,
                    )
                }
                ordered.map { item ->
                    if (item.embedding.isEmpty()) {
                        throw UpstreamLlmException(
                            "Upstream embeddings response contained an empty vector",
                            UpstreamLlmException.Kind.BAD_RESPONSE,
                        )
                    }
                    FloatArray(item.embedding.size) { index -> item.embedding[index].toFloat() }
                }
            }
            .doOnSuccess { sample.stop(llmTimer("embeddings", "success")) }
            .doOnError { sample.stop(llmTimer("embeddings", "error")) }
    }

    private fun <T> post(path: String, body: Any, type: Class<T>): Mono<T> =
        webClient.post()
            .uri(path.removePrefix("/"))
            .headers(::applyAuth)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(type)
            .timeout(properties.llm.timeout)
            .retryWhen(retrySpec())
            .onErrorMap(::mapError)

    private fun applyAuth(headers: HttpHeaders) {
        val apiKey = properties.llm.apiKey()
        if (apiKey.isNotBlank()) {
            headers.setBearerAuth(apiKey)
        }
    }

    private fun retrySpec(): Retry {
        if (properties.llm.maxRetries == 0) {
            return Retry.max(0)
        }
        return Retry.backoff(properties.llm.maxRetries.toLong(), java.time.Duration.ofMillis(200))
            .maxBackoff(java.time.Duration.ofSeconds(2))
            .jitter(0.2)
            .filter(::retryable)
            .onRetryExhaustedThrow { _, signal -> signal.failure() }
    }

    private fun retryable(error: Throwable): Boolean = when (error) {
        is WebClientResponseException ->
            error.statusCode.value() == 429 || error.statusCode.is5xxServerError
        is TimeoutException, is WebClientRequestException -> true
        else -> false
    }

    private fun mapError(error: Throwable): Throwable {
        if (error is UpstreamLlmException) {
            return error
        }
        return when (error) {
            is WebClientResponseException -> {
                log.warn(
                    "upstream status={} body={}",
                    error.statusCode.value(),
                    error.responseBodyAsString.take(300).replace(Regex("\\s+"), " "),
                )
                when {
                    error.statusCode.value() == 429 ->
                        UpstreamLlmException("Upstream LLM is rate limiting", UpstreamLlmException.Kind.RATE_LIMIT, error)
                    error.statusCode.is5xxServerError ->
                        UpstreamLlmException(
                            "Upstream LLM returned ${error.statusCode.value()}",
                            UpstreamLlmException.Kind.UNAVAILABLE,
                            error,
                        )
                    else ->
                        UpstreamLlmException(
                            "Upstream LLM returned ${error.statusCode.value()}",
                            UpstreamLlmException.Kind.BAD_RESPONSE,
                            error,
                        )
                }
            }
            is TimeoutException ->
                UpstreamLlmException("Upstream LLM timed out", UpstreamLlmException.Kind.TIMEOUT, error)
            else ->
                UpstreamLlmException("Upstream LLM call failed", UpstreamLlmException.Kind.UNAVAILABLE, error)
        }
    }

    private fun llmTimer(operation: String, outcome: String): Timer =
        meterRegistry.timer("rag.llm", "operation", operation, "outcome", outcome)

    private companion object {
        val log = LoggerFactory.getLogger(OpenAiCompatibleClient::class.java)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EmbeddingResponse(
    val data: List<EmbeddingData> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EmbeddingData(
    val index: Int = 0,
    val embedding: List<Double> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatCompletionResponse(
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatChoice(
    val message: ChatMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatMessage(
    val content: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatUsage(
    @param:JsonProperty("prompt_tokens") val promptTokens: Int? = null,
    @param:JsonProperty("completion_tokens") val completionTokens: Int? = null,
)
