package com.example.glmproxy

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.nio.charset.StandardCharsets

/**
 * Anthropic API의 실제 SSE 형식 캡처
 *
 * 목표: 실제 API가 보내는 이벤트 타입과 순서를 확인
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CaptureAnthropicSSEFormatTest {

    @Value("\${target.base-url}")
    private lateinit var targetBaseUrl: String

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `capture complete Anthropic SSE event flow`() {
        val request = """
            {
                "model": "claude-haiku-4-5-202501001",
                "messages": [
                    {
                        "role": "user",
                        "content": "Say hi"
                    }
                ],
                "max_tokens": 50,
                "stream": true
            }
        """.trimIndent()

        println("\n" + "=".repeat(80))
        println("🔍 CAPTURING ANTHROPIC API SSE FORMAT")
        println("=".repeat(80))
        println("📡 Target: $targetBaseUrl")
        println("=".repeat(80) + "\n")

        val events = mutableListOf<SSEEvent>()
        val eventOrder = mutableListOf<String>()

        webTestClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(request))
            .exchange()
            .returnResult(org.springframework.core.io.buffer.DataBuffer::class.java)
            .responseBody
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                String(bytes, StandardCharsets.UTF_8)
            }
            .doOnNext { chunk ->
                // 라인별 파싱
                val lines = chunk.split("\n")
                var currentEvent: String? = null
                var currentData: String? = null

                for (line in lines) {
                    when {
                        line.startsWith("event:") -> {
                            currentEvent = line.substring(6).trim()
                            println("📋 EVENT: $currentEvent")
                        }
                        line.startsWith("data:") -> {
                            currentData = line.substring(5).trim()
                            println("📦 DATA: $currentData")

                            if (currentEvent != null && currentData != null) {
                                val event = SSEEvent(currentEvent!!, currentData!!)
                                events.add(event)
                                eventOrder.add(currentEvent!!)

                                // 이벤트 타입별 상세 분석
                                when (currentEvent) {
                                    "message_start" -> parseMessageStart(currentData)
                                    "content_block_start" -> parseContentBlockStart(currentData)
                                    "content_block_delta" -> parseContentBlockDelta(currentData)
                                    "content_block_stop" -> println("   → Content block completed")
                                    "message_delta" -> parseMessageDelta(currentData)
                                    "message_stop" -> println("   → Message completed")
                                    "ping" -> println("   → Ping received")
                                    "error" -> println("   ❌ ERROR: $currentData")
                                    else -> println("   ⚠️  Unknown event type: $currentEvent")
                                }
                            }
                        }
                        line.isEmpty() && currentEvent != null -> {
                            // 빈 줄 = 이벤트 끝
                            println()
                            currentEvent = null
                            currentData = null
                        }
                    }
                }
            }
            .collectList()
            .block()

        println("\n" + "=".repeat(80))
        println("📊 EVENT FLOW ANALYSIS")
        println("=".repeat(80))
        println("총 이벤트 수: ${events.size}")
        println()

        println("이벤트 순서:")
        eventOrder.forEachIndexed { index, eventType ->
            println("  ${index + 1}. $eventType")
        }

        println("\n이벤트 타입별 개수:")
        events.groupBy { it.type }
            .mapValues { it.value.size }
            .forEach { (type, count) ->
                println("  - $type: $count times")
            }

        println("\n" + "=".repeat(80))
        println("✅ CAPTURE COMPLETE")
        println("=".repeat(80) + "\n")
    }

    private fun parseMessageStart(data: String) {
        try {
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(data)
            println("   → Message started")
            println("      - type: ${json.get("type").asText()}")
            if (json.has("message")) {
                val message = json.get("message")
                println("      - id: ${message.get("id").asText()}")
                println("      - role: ${message.get("role").asText()}")
            }
        } catch (e: Exception) {
            println("   ⚠️  Parse error: ${e.message}")
        }
    }

    private fun parseContentBlockStart(data: String) {
        try {
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(data)
            println("   → Content block started")
            println("      - index: ${json.get("index").asInt()}")
            if (json.has("content_block")) {
                val block = json.get("content_block")
                println("      - type: ${block.get("type").asText()}")
            }
        } catch (e: Exception) {
            println("   ⚠️  Parse error: ${e.message}")
        }
    }

    private fun parseContentBlockDelta(data: String) {
        try {
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(data)
            println("   → Content block delta")
            println("      - index: ${json.get("index").asInt()}")
            if (json.has("delta")) {
                val delta = json.get("delta")
                if (delta.has("type")) {
                    println("      - delta.type: ${delta.get("type").asText()}")
                }
                if (delta.has("text")) {
                    val text = delta.get("text").asText()
                    println("      - delta.text: '$text'")
                }
            }
        } catch (e: Exception) {
            println("   ⚠️  Parse error: ${e.message}")
        }
    }

    private fun parseMessageDelta(data: String) {
        try {
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(data)
            println("   → Message delta")
            if (json.has("delta")) {
                val delta = json.get("delta")
                if (delta.has("stop_reason")) {
                    println("      - stop_reason: ${delta.get("stop_reason").asText()}")
                }
            }
            if (json.has("usage")) {
                val usage = json.get("usage")
                println("      - output_tokens: ${usage.get("output_tokens").asInt()}")
            }
        } catch (e: Exception) {
            println("   ⚠️  Parse error: ${e.message}")
        }
    }

    data class SSEEvent(
        val type: String,
        val data: String
    )
}
