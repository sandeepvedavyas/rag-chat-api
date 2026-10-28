package com.sandeepvedavyas.rag.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.sandeepvedavyas.rag.chat.ChatService
import com.sandeepvedavyas.rag.support.BagOfWords
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "15s")
@AutoConfigureObservability
class RagApiIntegrationTest @Autowired constructor(
    private val webTestClient: WebTestClient,
) {
    @Test
    fun `health and service root do not require a key`() {
        webTestClient.get().uri("/actuator/health")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.components.index.details.documents").exists()

        webTestClient.get().uri("/")
            .header("X-Request-Id", "demo-123")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Request-Id", "demo-123")
            .expectBody()
            .jsonPath("$.chat").isEqualTo("/api/v1/chat")
    }

    @Test
    fun `protected routes reject a missing or wrong bearer token`() {
        webTestClient.post().uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("question" to "hello"))
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"rag-chat-api\"")
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.detail").isEqualTo("Missing or invalid bearer token")

        webTestClient.get().uri("/actuator/prometheus")
            .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
            .exchange()
            .expectStatus().isUnauthorized

        authed().get().uri("/actuator/prometheus")
            .accept(MediaType.TEXT_PLAIN)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value(containsString("jvm_"))
    }

    @Test
    fun `ingests json and a matching upload once, then exposes the duplicate metric`() {
        val content = "The marigold ledger entry 8841 stays immutable after it is sealed."
        val created = authed().post().uri("/api/v1/documents")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Ledger", "content" to content))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.created").isEqualTo(true)
            .jsonPath("$.sourceName").isEqualTo("Ledger")
            .jsonPath("$.chunkCount").isEqualTo(1)
            .returnResult()

        val id = documentId(created.responseBody)

        authed().post().uri("/api/v1/documents/upload")
            .body(BodyInserters.fromMultipartData(multipart(content, "notes.md")))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.created").isEqualTo(false)
            .jsonPath("$.id").isEqualTo(id)
            .jsonPath("$.title").isEqualTo("Ledger")

        authed().get().uri("/api/v1/documents/$id")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.chunkCount").isEqualTo(1)

        authed().get().uri("/actuator/prometheus")
            .accept(MediaType.TEXT_PLAIN)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value(containsString("rag_ingest_duplicate_total"))
    }

    @Test
    fun `rejects an unsupported upload, an empty body, and an oversized body`() {
        authed().post().uri("/api/v1/documents/upload")
            .body(BodyInserters.fromMultipartData(multipart("not a pdf", "notes.pdf")))
            .exchange()
            .expectStatus().isEqualTo(415)

        authed().post().uri("/api/v1/documents")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Empty", "content" to "   "))
            .exchange()
            .expectStatus().isBadRequest

        authed().post().uri("/api/v1/documents")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Big", "content" to "a".repeat(5_000)))
            .exchange()
            .expectStatus().isEqualTo(413)
            .expectBody()
            .jsonPath("$.detail").value(containsString("exceeds"))
    }

    @Test
    fun `chat returns the upstream answer and a citation, and abstains without calling chat`() {
        val beforeChats = chatCalls.get()
        authed().post().uri("/api/v1/documents")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Parcel lock", "content" to PARCEL_DOC, "sourceName" to "notes.md"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.chunkCount").isEqualTo(2)

        val question = "What does the parcel-lock protocol do to child coroutines when the parent scope is cancelled?"
        authed().post().uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("question" to question, "topK" to 4))
            .exchange()
            .expectStatus().isOk
            .expectHeader().exists("X-Request-Id")
            .expectBody()
            .jsonPath("$.model").isEqualTo("test-chat")
            .jsonPath("$.answer").value(containsString("[1]"))
            .jsonPath("$.citations[0].title").isEqualTo("Parcel lock")
            .jsonPath("$.citations[0].sourceName").isEqualTo("notes.md")
            .jsonPath("$.citations[0].text").value(containsString("parcel-lock"))
            .jsonPath("$.citations[0].score").exists()

        assertThat(chatCalls.get()).isEqualTo(beforeChats + 1)
        assertThat(lastChatBody.get()).contains(question).contains("parcel-lock")

        val chatsAfterAnswer = chatCalls.get()
        authed().post().uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("question" to "How is quantum chromodynamics lattice spacing measured?"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.answer").isEqualTo(ChatService.NO_CONTEXT_ANSWER)
            .jsonPath("$.model").isEmpty()
            .jsonPath("$.citations").isEmpty()
        assertThat(chatCalls.get()).isEqualTo(chatsAfterAnswer)

        authed().post().uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("question" to "hi", "topK" to 50))
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `delete drops a document so a later question abstains`() {
        val content = "zephyrwidget calibration retires the spool when the latch closes."
        val created = authed().post().uri("/api/v1/documents")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("title" to "Zephyr", "content" to content, "sourceName" to "zephyr.md"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
        val id = documentId(created.responseBody)
        val chatsBefore = chatCalls.get()

        authed().delete().uri("/api/v1/documents/$id")
            .exchange()
            .expectStatus().isNoContent
        authed().get().uri("/api/v1/documents/$id")
            .exchange()
            .expectStatus().isNotFound
        authed().delete().uri("/api/v1/documents/$id")
            .exchange()
            .expectStatus().isNotFound

        authed().post().uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("question" to "When does zephyrwidget calibration retire the spool?"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.answer").isEqualTo(ChatService.NO_CONTEXT_ANSWER)
        assertThat(chatCalls.get()).isEqualTo(chatsBefore)
    }

    private fun authed(): WebTestClient = webTestClient.mutate()
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key")
        .build()

    private fun multipart(content: String, filename: String) : org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<*>> {
        val builder = org.springframework.http.client.MultipartBodyBuilder()
        builder.part("file", content.toByteArray(Charsets.UTF_8)).filename(filename)
        return builder.build()
    }

    private fun documentId(body: ByteArray?): String {
        val tree = ObjectMapper().readTree(body)
        return tree.get("id").asText()
    }

    companion object {
        private val server = MockWebServer()
        private val chatCalls = AtomicInteger()
        private val lastChatBody = AtomicReference("")
        private val mapper = ObjectMapper()

        private val PARCEL_DOC = """
            # Parcel lock

            The parcel-lock protocol cancels child coroutines when the parent scope is cancelled.

            # Baking

            Sourdough bread needs a starter, flour, water, and a long ferment before baking.
        """.trimIndent()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?').orEmpty()
                    if (request.getHeader("Authorization") != "Bearer test-upstream-key") {
                        return MockResponse().setResponseCode(401).setBody("""{"error":"missing upstream key"}""")
                    }
                    val body = request.body.readUtf8()
                    return when (path) {
                        "/v1/embeddings" -> embeddings(body)
                        "/v1/chat/completions" -> chat(body)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        @JvmStatic
        @AfterAll
        fun shutdown() {
            server.shutdown()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("rag.llm.openai-base-url") { server.url("/v1").toString() }
            registry.add("rag.llm.openai-api-key") { "test-upstream-key" }
            registry.add("rag.auth.api-key") { "test-key" }
            registry.add("rag.retrieval.min-score") { "0.5" }
            registry.add("rag.ingest.chunk-size") { "120" }
            registry.add("rag.ingest.chunk-overlap") { "20" }
            registry.add("rag.ingest.max-bytes") { "4096" }
        }

        private fun embeddings(body: String): MockResponse {
            val input = mapper.readTree(body).get("input")
            val texts = when {
                input == null -> emptyList()
                input.isArray -> input.map { it.asText() }
                else -> listOf(input.asText())
            }
            val data = texts.mapIndexed { index, text ->
                mapOf(
                    "object" to "embedding",
                    "index" to index,
                    "embedding" to BagOfWords.embed(text).map { it.toDouble() },
                )
            }
            return json(mapper.writeValueAsString(mapOf("object" to "list", "data" to data, "model" to "test-embed")))
        }

        private fun chat(body: String): MockResponse {
            chatCalls.incrementAndGet()
            lastChatBody.set(body)
            return json(
                """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "model": "test-chat",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Child coroutines are cancelled when the parent scope is cancelled [1]."
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 20, "completion_tokens": 12, "total_tokens": 32}
                }
                """.trimIndent(),
            )
        }

        private fun json(body: String): MockResponse =
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
    }
}
