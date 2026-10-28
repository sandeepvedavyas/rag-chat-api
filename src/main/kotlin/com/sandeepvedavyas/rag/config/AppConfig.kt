package com.sandeepvedavyas.rag.config

import com.sandeepvedavyas.rag.ingest.TextChunker
import com.sandeepvedavyas.rag.llm.OpenAiCompatibleClient
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ChannelOption
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

@Configuration
class AppConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun textChunker(properties: RagProperties): TextChunker =
        TextChunker(properties.ingest.chunkSize, properties.ingest.chunkOverlap)

    @Bean
    fun openAiCompatibleClient(
        builder: WebClient.Builder,
        properties: RagProperties,
        meterRegistry: MeterRegistry,
    ): OpenAiCompatibleClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.llm.timeout.toMillis().toInt())
            .responseTimeout(properties.llm.timeout)
        val webClient = builder
            .baseUrl(properties.llm.baseUrl())
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.USER_AGENT, "rag-chat-api/0.1")
            .build()
        return OpenAiCompatibleClient(webClient, properties, meterRegistry)
    }

    @Bean
    fun startupChecks(properties: RagProperties): ApplicationRunner = ApplicationRunner { _: ApplicationArguments ->
        if (properties.auth.apiKey.isBlank()) {
            log.warn("RAG_API_KEY is blank. Every protected request will be rejected.")
        } else if (properties.auth.apiKey == RagProperties.LOCAL_DEV_API_KEY) {
            log.warn(
                "RAG_API_KEY is the local-dev default. Set a unique key before exposing this service.",
            )
        }
        log.info(
            "llm baseUrl={} viaGateway={} chatModel={} embeddingModel={} upstreamKeyPresent={}",
            properties.llm.displayBaseUrl(),
            properties.llm.usesGateway(),
            properties.llm.chatModel,
            properties.llm.embeddingModel,
            properties.llm.apiKey().isNotBlank(),
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(AppConfig::class.java)
    }
}
