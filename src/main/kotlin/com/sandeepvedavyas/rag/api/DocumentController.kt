package com.sandeepvedavyas.rag.api

import com.sandeepvedavyas.rag.config.RagProperties
import com.sandeepvedavyas.rag.domain.Rejection
import com.sandeepvedavyas.rag.domain.RequestRejectedException
import com.sandeepvedavyas.rag.ingest.DocumentTexts
import com.sandeepvedavyas.rag.ingest.IngestCommand
import com.sandeepvedavyas.rag.ingest.IngestService
import com.sandeepvedavyas.rag.ingest.IngestedDocument
import com.sandeepvedavyas.rag.ingest.InvalidUtf8Exception
import com.sandeepvedavyas.rag.retrieval.DocumentRecord
import com.sandeepvedavyas.rag.retrieval.VectorStore
import jakarta.validation.Valid
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1/documents")
class DocumentController(
    private val ingestService: IngestService,
    private val vectorStore: VectorStore,
    private val properties: RagProperties,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun ingest(@Valid @RequestBody request: IngestDocumentRequest): Mono<DocumentResponse> =
        ingestService.ingest(
            IngestCommand(
                title = request.title,
                content = request.content,
                sourceName = request.sourceName?.trim().takeUnless { it.isNullOrEmpty() } ?: request.title.trim(),
            ),
        ).map { it.toResponse() }

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("file") file: FilePart,
        @RequestParam(name = "title", required = false) title: String?,
    ): Mono<DocumentResponse> {
        if (!supportedUpload(file)) {
            return Mono.error(
                RequestRejectedException(
                    Rejection.UNSUPPORTED_MEDIA,
                    "Upload a UTF-8 .txt, .md, or .markdown file",
                ),
            )
        }
        val limit = properties.ingest.maxBytes
        return DataBufferUtils.join(file.content(), limit)
            .onErrorMap(DataBufferLimitException::class.java) {
                RequestRejectedException(Rejection.TOO_LARGE, "Document exceeds $limit bytes")
            }
            .map { buffer -> readBytes(buffer) }
            .flatMap { bytes ->
                val text = try {
                    DocumentTexts.decodeUtf8(bytes)
                } catch (ex: InvalidUtf8Exception) {
                    return@flatMap Mono.error(
                        RequestRejectedException(Rejection.BAD_REQUEST, ex.message ?: "Document must be UTF-8 text"),
                    )
                }
                val resolvedTitle = title?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: file.filename().substringBeforeLast('.').ifBlank { "untitled" }
                val sourceName = file.filename().ifBlank { "$resolvedTitle.md" }
                ingestService.ingest(IngestCommand(resolvedTitle, text, sourceName))
            }
            .map { it.toResponse() }
    }

    @GetMapping
    fun list(): Mono<List<DocumentResponse>> =
        Mono.fromSupplier { vectorStore.listDocuments().map { it.toResponse() } }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): Mono<DocumentResponse> =
        Mono.fromSupplier {
            vectorStore.findById(id)?.toResponse()
                ?: throw RequestRejectedException(Rejection.NOT_FOUND, "Document not found")
        }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Mono<ResponseEntity<Void>> =
        Mono.fromSupplier {
            if (vectorStore.delete(id)) {
                ResponseEntity.noContent().build()
            } else {
                throw RequestRejectedException(Rejection.NOT_FOUND, "Document not found")
            }
        }

    private fun supportedUpload(file: FilePart): Boolean {
        val extension = file.filename().substringAfterLast('.', "").lowercase()
        if (extension in ALLOWED_EXTENSIONS) {
            return true
        }
        if (extension.isNotEmpty()) {
            return false
        }
        val contentType = file.headers().contentType?.toString()?.substringBefore(';')?.lowercase()
        return contentType in ALLOWED_TYPES
    }

    private fun readBytes(buffer: DataBuffer): ByteArray =
        try {
            val bytes = ByteArray(buffer.readableByteCount())
            buffer.read(bytes)
            bytes
        } finally {
            DataBufferUtils.release(buffer)
        }

    private fun IngestedDocument.toResponse() = DocumentResponse(
        id = id,
        title = title,
        sourceName = sourceName,
        contentHash = contentHash,
        chunkCount = chunkCount,
        createdAt = createdAt,
        created = created,
    )

    private fun DocumentRecord.toResponse() = DocumentResponse(
        id = id,
        title = title,
        sourceName = sourceName,
        contentHash = contentHash,
        chunkCount = chunkCount,
        createdAt = createdAt,
    )

    companion object {
        private val ALLOWED_EXTENSIONS = setOf("txt", "md", "markdown")
        private val ALLOWED_TYPES = setOf("text/plain", "text/markdown", "text/x-markdown")
    }
}
