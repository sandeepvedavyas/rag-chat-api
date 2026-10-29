package com.sandeepvedavyas.rag.ingest

import com.sandeepvedavyas.rag.config.RagProperties
import com.sandeepvedavyas.rag.domain.Rejection
import com.sandeepvedavyas.rag.domain.RequestRejectedException
import com.sandeepvedavyas.rag.llm.EmbeddingModel
import com.sandeepvedavyas.rag.retrieval.DocumentRecord
import com.sandeepvedavyas.rag.retrieval.StoredChunk
import com.sandeepvedavyas.rag.retrieval.VectorStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

data class IngestCommand(
    val title: String,
    val content: String,
    val sourceName: String,
)

data class IngestedDocument(
    val id: String,
    val title: String,
    val sourceName: String,
    val contentHash: String,
    val chunkCount: Int,
    val createdAt: Instant,
    val created: Boolean,
)

@Service
class IngestService(
    private val chunker: TextChunker,
    private val embeddingModel: EmbeddingModel,
    private val vectorStore: VectorStore,
    private val properties: RagProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    fun ingest(command: IngestCommand): Mono<IngestedDocument> = Mono.defer { ingestNow(command) }

    private fun ingestNow(command: IngestCommand): Mono<IngestedDocument> {
        val title = command.title.trim()
        val sourceName = command.sourceName.trim()
        if (title.isEmpty() || title.length > 200) {
            return Mono.error(RequestRejectedException(Rejection.BAD_REQUEST, "Title must be 1 to 200 characters"))
        }
        if (sourceName.isEmpty() || sourceName.length > 200) {
            return Mono.error(RequestRejectedException(Rejection.BAD_REQUEST, "Source name must be 1 to 200 characters"))
        }
        val normalized = DocumentTexts.normalize(command.content)
        if (normalized.isEmpty()) {
            return Mono.error(RequestRejectedException(Rejection.BAD_REQUEST, "Document content is empty"))
        }
        val size = normalized.toByteArray(Charsets.UTF_8).size
        if (size > properties.ingest.maxBytes) {
            return Mono.error(
                RequestRejectedException(
                    Rejection.TOO_LARGE,
                    "Document exceeds ${properties.ingest.maxBytes} bytes",
                ),
            )
        }
        val hash = DocumentTexts.sha256(normalized)
        vectorStore.findByHash(hash)?.let { existing ->
            meterRegistry.counter("rag.ingest.duplicate").increment()
            log.info("ingest duplicate id={} chunks={}", existing.id, existing.chunkCount)
            return Mono.just(existing.toIngested(created = false))
        }
        val parts = chunker.chunk(normalized)
        if (parts.isEmpty()) {
            return Mono.error(RequestRejectedException(Rejection.BAD_REQUEST, "Document content is empty"))
        }
        val sample = Timer.start(meterRegistry)
        return embeddingModel.embed(parts)
            .map { vectors ->
                if (vectors.size != parts.size) {
                    throw IllegalStateException("Embedder returned ${vectors.size} vectors for ${parts.size} chunks")
                }
                val id = UUID.randomUUID().toString()
                val createdAt = Instant.now(clock)
                val stored = parts.zip(vectors).mapIndexed { ordinal, (text, vector) ->
                    StoredChunk(
                        id = "$id:$ordinal",
                        documentId = id,
                        title = title,
                        sourceName = sourceName,
                        ordinal = ordinal,
                        text = text,
                        vector = vector,
                    )
                }
                val record = DocumentRecord(
                    id = id,
                    title = title,
                    sourceName = sourceName,
                    contentHash = hash,
                    createdAt = createdAt,
                    chunkCount = stored.size,
                )
                val inserted = vectorStore.insert(record, stored)
                if (inserted.created) {
                    log.info("ingested document id={} chunks={} bytes={}", inserted.document.id, stored.size, size)
                } else {
                    log.info("ingest duplicate id={} chunks={}", inserted.document.id, inserted.document.chunkCount)
                }
                inserted.document.toIngested(created = inserted.created)
            }
            .doOnSuccess { ingested ->
                val outcome = if (ingested.created) "created" else "duplicate"
                sample.stop(meterRegistry.timer("rag.ingest", "outcome", outcome))
            }
            .doOnError { error ->
                sample.stop(meterRegistry.timer("rag.ingest", "outcome", "error"))
                log.warn("ingest failed: {}", error.message)
            }
    }

    private fun DocumentRecord.toIngested(created: Boolean) = IngestedDocument(
        id = id,
        title = title,
        sourceName = sourceName,
        contentHash = contentHash,
        chunkCount = chunkCount,
        createdAt = createdAt,
        created = created,
    )

    private companion object {
        val log = LoggerFactory.getLogger(IngestService::class.java)
    }
}
