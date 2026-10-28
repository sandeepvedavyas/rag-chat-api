package com.sandeepvedavyas.rag.config

import io.micrometer.context.ContextRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.MDC
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Hooks

@Configuration
class RequestContextConfig {
    @PostConstruct
    fun registerRequestIdBridge() {
        Hooks.enableAutomaticContextPropagation()
        try {
            ContextRegistry.getInstance().registerThreadLocalAccessor(
                REQUEST_ID,
                { MDC.get(REQUEST_ID) },
                { value: String -> MDC.put(REQUEST_ID, value) },
                { MDC.remove(REQUEST_ID) },
            )
        } catch (_: IllegalArgumentException) {
            // The registry is JVM-global. A second application context in tests hits this.
        }
    }

    companion object {
        const val REQUEST_ID = "requestId"
    }
}
