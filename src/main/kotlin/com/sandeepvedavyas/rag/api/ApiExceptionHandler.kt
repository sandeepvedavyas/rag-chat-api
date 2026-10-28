package com.sandeepvedavyas.rag.api

import com.sandeepvedavyas.rag.domain.Rejection
import com.sandeepvedavyas.rag.domain.RequestRejectedException
import com.sandeepvedavyas.rag.llm.UpstreamLlmException
import com.sandeepvedavyas.rag.retrieval.VectorDimensionException
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(RequestRejectedException::class)
    fun rejected(ex: RequestRejectedException): ProblemDetail {
        val status = when (ex.rejection) {
            Rejection.BAD_REQUEST -> HttpStatus.BAD_REQUEST
            Rejection.TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE
            Rejection.UNSUPPORTED_MEDIA -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
            Rejection.NOT_FOUND -> HttpStatus.NOT_FOUND
            Rejection.CONFLICT -> HttpStatus.CONFLICT
        }
        return ProblemDetail.forStatusAndDetail(status, ex.message)
    }

    @ExceptionHandler(UpstreamLlmException::class)
    fun upstream(ex: UpstreamLlmException): ProblemDetail {
        log.warn("upstream failure kind={} message={}", ex.kind, ex.message)
        val status = when (ex.kind) {
            UpstreamLlmException.Kind.TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
            UpstreamLlmException.Kind.RATE_LIMIT -> HttpStatus.SERVICE_UNAVAILABLE
            UpstreamLlmException.Kind.UNAVAILABLE, UpstreamLlmException.Kind.BAD_RESPONSE -> HttpStatus.BAD_GATEWAY
        }
        return ProblemDetail.forStatusAndDetail(status, ex.message ?: "Upstream LLM call failed")
    }

    @ExceptionHandler(VectorDimensionException::class)
    fun dimensions(ex: VectorDimensionException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message ?: "Embedding dimension mismatch")

    @ExceptionHandler(DataBufferLimitException::class)
    fun tooLarge(@Suppress("UNUSED_PARAMETER") ex: DataBufferLimitException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Document exceeds the configured size limit")

    private companion object {
        val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
