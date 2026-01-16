package com.example.glmproxy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.client.WebClient

@SpringBootApplication
class GlmProxyApplication {

    @Bean
    fun webClientBuilder(): WebClient.Builder {
        // 기본 설정 사용 (timeout 제거)
        return WebClient.builder()
    }
}

fun main(args: Array<String>) {
    runApplication<GlmProxyApplication>(*args)
}
