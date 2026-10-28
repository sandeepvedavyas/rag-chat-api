package com.sandeepvedavyas.rag.auth

import com.sandeepvedavyas.rag.config.RequestContextConfig
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.util.context.Context

@Component
class RequestIdWebFilter : WebFilter, Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val requestId = exchange.request.headers.getFirst(HEADER)
            ?.trim()
            ?.takeIf { it.length in 1..128 && it.all { char -> char.isLetterOrDigit() || char in "-_." } }
            ?: UUID.randomUUID().toString()
        exchange.response.headers.set(HEADER, requestId)
        exchange.attributes[HEADER] = requestId
        val path = exchange.request.path.pathWithinApplication().value()
        if (path.startsWith("/api/")) {
            exchange.response.beforeCommit {
                Mono.fromRunnable {
                    log.info(
                        "request method={} path={} status={} requestId={}",
                        exchange.request.method,
                        path,
                        exchange.response.statusCode?.value(),
                        requestId,
                    )
                }
            }
        }
        return chain.filter(exchange).contextWrite(Context.of(RequestContextConfig.REQUEST_ID, requestId))
    }

    companion object {
        const val HEADER = "X-Request-Id"
        private val log = LoggerFactory.getLogger(RequestIdWebFilter::class.java)
    }
}
