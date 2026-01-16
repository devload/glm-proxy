package com.example.glmproxy

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
class ProxyController(
    private val proxyService: ProxyService
) {

    /**
     * 테스트용 엔드포인트:我们自己가 만든 SSE 이벤트들을 직접 전송
     * 사용법: curl -N http://localhost:8080/test/events
     */
    @RequestMapping("/test/events")
    fun testEvents(exchange: ServerWebExchange): Mono<Void> {
        return proxyService.sendTestEvents(exchange)
    }

    /**
     * 테스트용 엔드포인트 (CLAUDE CODE 호환)
     * CLAUDE CODE는 /v1/messages로 요청을 보내므로 이 경로도 지원
     */
    @RequestMapping("/test/v1/messages")
    fun testMessages(exchange: ServerWebExchange): Mono<Void> {
        return proxyService.sendTestEvents(exchange)
    }

    /**
     * 프록시 엔드포인트: 모든 요청을 대상 서버로 전달
     * 주의: 반드시 가장 마지막에 정의되어야 함 (catch-all)
     */
    @RequestMapping("/**")
    fun proxyRequest(exchange: ServerWebExchange): Mono<Void> {
        return proxyService.proxyRequest(exchange)
    }
}
