package com.sandeepvedavyas.rag

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class RagChatApplication

fun main(args: Array<String>) {
    runApplication<RagChatApplication>(*args)
}
