package com.sandeepvedavyas.rag.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RagPropertiesTest {
    @Test
    fun `gateway url and key override the openai pair`() {
        val properties = RagProperties(
            llm = RagProperties.Llm(
                openaiBaseUrl = "https://api.openai.com/v1",
                openaiApiKey = "sk-openai",
                gatewayBaseUrl = "http://gateway.internal:8081/v1",
                gatewayApiKey = "gateway-key",
            ),
        )

        assertThat(properties.llm.usesGateway()).isTrue()
        assertThat(properties.llm.baseUrl()).isEqualTo("http://gateway.internal:8081/v1/")
        assertThat(properties.llm.apiKey()).isEqualTo("gateway-key")
        assertThat(properties.llm.displayBaseUrl()).isEqualTo("http://gateway.internal:8081/v1")
    }

    @Test
    fun `a gateway key alone does not replace the openai key`() {
        val properties = RagProperties(
            llm = RagProperties.Llm(
                openaiBaseUrl = "https://api.openai.com/v1",
                openaiApiKey = "sk-openai",
                gatewayApiKey = "gateway-key",
            ),
        )

        assertThat(properties.llm.usesGateway()).isFalse()
        assertThat(properties.llm.apiKey()).isEqualTo("sk-openai")
    }

    @Test
    fun `overlap must be smaller than the chunk`() {
        assertThatThrownBy {
            RagProperties(ingest = RagProperties.Ingest(chunkSize = 200, chunkOverlap = 200))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
