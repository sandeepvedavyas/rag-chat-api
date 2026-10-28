package com.sandeepvedavyas.rag.llm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.sandeepvedavyas.rag.config.RagProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.function.client.WebClient
import reactor.test.StepVerifier
import com.fasterxml.jackson.databind.json.JsonMapper

class OpenAiCompatibleClientTest {
    private val server = MockWebServer()
    private val meterRegistry = SimpleMeterRegistry()

    @BeforeEach
    fun start() {
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `sorts embedding rows by index and sends the bearer token`() {
        server.enqueue(json(
            """
            {"object":"list","model":"text-embedding-3-small","data":[
              {"object":"embedding","index":1,"embedding":[0.0,1.0]},
              {"object":"embedding","index":0,"embedding":[1.0,0.0]}
            ]}
            """.trimIndent(),
        ))

        StepVerifier.create(client().embed(listOf("first", "second")))
            .assertNext { vectors ->
                assertThat(vectors).hasSize(2)
                assertThat(vectors[0]).containsExactly(1.0f, 0.0f)
                assertThat(vectors[1]).containsExactly(0.0f, 1.0f)
            }
            .verifyComplete()

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/embeddings")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-upstream-key")
        assertThat(recorded.body.readUtf8()).contains("\"first\"").contains("\"second\"")
    }

    @Test
    fun `omits authorization when no upstream key is configured`() {
        server.enqueue(json("""{"data":[{"index":0,"embedding":[0.2]}]}"""))

        StepVerifier.create(client(apiKey = "").embed(listOf("only")))
            .expectNextCount(1)
            .verifyComplete()

        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `retries a 500 once and does not retry a 400`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("unavailable"))
        server.enqueue(json(
            """{"model":"llama3.2","choices":[{"message":{"role":"assistant","content":"grounded [1]"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}""",
        ))

        StepVerifier.create(client(maxRetries = 1).complete("system", "user"))
            .assertNext { completion ->
                assertThat(completion.content).isEqualTo("grounded [1]")
                assertThat(completion.model).isEqualTo("llama3.2")
            }
            .verifyComplete()
        assertThat(server.requestCount).isEqualTo(2)

        server.enqueue(MockResponse().setResponseCode(400).setBody("bad model"))
        StepVerifier.create(client(maxRetries = 2).embed(listOf("nope")))
            .expectErrorSatisfies { error ->
                assertThat(error).isInstanceOf(UpstreamLlmException::class.java)
                assertThat((error as UpstreamLlmException).kind).isEqualTo(UpstreamLlmException.Kind.BAD_RESPONSE)
                assertThat(error.message).contains("400")
            }
            .verify()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `maps an upstream timeout kind separately from a bad payload`() {
        server.enqueue(json("""{"model":"llama3.2","choices":[{"message":{"content":"  "}}]}"""))

        StepVerifier.create(client().complete("system", "user"))
            .expectErrorSatisfies { error ->
                assertThat(error).isInstanceOf(UpstreamLlmException::class.java)
                assertThat((error as UpstreamLlmException).kind).isEqualTo(UpstreamLlmException.Kind.BAD_RESPONSE)
            }
            .verify()
    }

    private fun client(apiKey: String = "test-upstream-key", maxRetries: Int = 0): OpenAiCompatibleClient {
        val properties = RagProperties(
            llm = RagProperties.Llm(
                openaiBaseUrl = server.url("/v1").toString(),
                openaiApiKey = apiKey,
                maxRetries = maxRetries,
                timeout = Duration.ofSeconds(2),
            ),
        )
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()
        val webClient = WebClient.builder()
            .baseUrl(properties.llm.baseUrl())
            .codecs { codecs ->
                codecs.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(mapper))
                codecs.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(mapper))
            }
            .build()
        return OpenAiCompatibleClient(webClient, properties, meterRegistry)
    }

    private fun json(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
