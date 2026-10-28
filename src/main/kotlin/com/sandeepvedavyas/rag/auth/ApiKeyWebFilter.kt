package com.sandeepvedavyas.rag.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.sandeepvedavyas.rag.config.RagProperties
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class ApiKeyWebFilter(
    properties: RagProperties,
    private val objectMapper: ObjectMapper,
) : WebFilter, Ordered {
    private val verifier = ApiKeyVerifier(properties.auth.apiKey)

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 20

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication().value()
        if (isPublic(path) || verifier.matches(exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION))) {
            return chain.filter(exchange)
        }
        log.warn("rejected request method={} path={} reason=unauthorized", exchange.request.method, path)
        return unauthorized(exchange)
    }

    private fun unauthorized(exchange: ServerWebExchange): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.UNAUTHORIZED
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"rag-chat-api\"")
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token")
        val requestId = response.headers.getFirst(RequestIdWebFilter.HEADER)
        if (requestId != null) {
            problem.setProperty("requestId", requestId)
        }
        val bytes = objectMapper.writeValueAsBytes(problem)
        val buffer = response.bufferFactory().wrap(bytes)
        return response.writeWith(Mono.just(buffer))
    }

    private fun isPublic(path: String): Boolean = when {
        path == "/" -> true
        path == "/actuator/health" || path.startsWith("/actuator/health/") -> true
        path == "/actuator/info" -> true
        else -> false
    }

    private companion object {
        val log = LoggerFactory.getLogger(ApiKeyWebFilter::class.java)
    }
}
