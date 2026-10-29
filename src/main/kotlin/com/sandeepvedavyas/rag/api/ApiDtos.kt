package com.sandeepvedavyas.rag.api

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class IngestDocumentRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,
    @field:NotBlank
    val content: String,
    @field:Size(max = 200)
    val sourceName: String? = null,
)

data class DocumentResponse(
    val id: String,
    val title: String,
    val sourceName: String,
    val contentHash: String,
    val chunkCount: Int,
    val createdAt: Instant,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val created: Boolean? = null,
)

data class ChatRequest(
    @field:NotBlank
    @field:Size(max = 4000)
    val question: String,
    @field:Min(1)
    @field:Max(20)
    val topK: Int? = null,
)

data class ChatResponse(
    val answer: String,
    val model: String?,
    val citations: List<CitationResponse>,
)

data class CitationResponse(
    val index: Int,
    val documentId: String,
    val chunkId: String,
    val title: String,
    val sourceName: String,
    val score: Double,
    val text: String,
)
