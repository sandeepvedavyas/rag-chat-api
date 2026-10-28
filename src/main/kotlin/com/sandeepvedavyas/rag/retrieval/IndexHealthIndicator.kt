package com.sandeepvedavyas.rag.retrieval

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.ReactiveHealthIndicator
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class IndexHealthIndicator(
    private val vectorStore: VectorStore,
) : ReactiveHealthIndicator {
    override fun health(): Mono<Health> = Mono.fromSupplier {
        Health.up()
            .withDetail("documents", vectorStore.countDocuments())
            .withDetail("chunks", vectorStore.countChunks())
            .build()
    }
}
