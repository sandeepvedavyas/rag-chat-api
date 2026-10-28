package com.sandeepvedavyas.rag.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class RootController {
    @GetMapping("/")
    fun root(): Map<String, String> = mapOf(
        "service" to "rag-chat-api",
        "health" to "/actuator/health",
        "documents" to "/api/v1/documents",
        "chat" to "/api/v1/chat",
    )
}
