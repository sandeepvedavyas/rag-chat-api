package com.sandeepvedavyas.rag.api

import com.sandeepvedavyas.rag.chat.ChatAnswer
import com.sandeepvedavyas.rag.chat.ChatService
import com.sandeepvedavyas.rag.chat.Citation
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1/chat", produces = [MediaType.APPLICATION_JSON_VALUE])
class ChatController(
    private val chatService: ChatService,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun chat(@Valid @RequestBody request: ChatRequest): Mono<ChatResponse> =
        chatService.answer(request.question, request.topK).map { it.toResponse() }

    private fun ChatAnswer.toResponse() = ChatResponse(
        answer = answer,
        model = model,
        citations = citations.map { it.toResponse() },
    )

    private fun Citation.toResponse() = CitationResponse(
        index = index,
        documentId = documentId,
        chunkId = chunkId,
        title = title,
        sourceName = sourceName,
        score = score,
        text = text,
    )
}
