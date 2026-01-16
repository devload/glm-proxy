package com.example.glmproxy

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.nio.charset.StandardCharsets

/**
 * 실제 Anthropic API 응답을 RAW 형태로 캡처
 *
 * 목표: PII 마스킹 없이 순수 API 응답만 캡처해서 SSE 형식 분석
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CaptureRealAPIResponseTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `capture raw Anthropic API response with PII masking disabled`() {
        val request = """
            {
                "model": "claude-haiku-4-5-202501001",
                "messages": [
                    {
                        "role": "user",
                        "content": "Say hello in one sentence"
                    }
                ],
                "max_tokens": 100,
                "stream": true
            }
        """.trimIndent()

        println("\n" + "=".repeat(80))
        println("🔍 CAPTURING RAW ANTHROPIC API RESPONSE")
        println("=".repeat(80))
        println("📤 Request (PII masking: DISABLED)")
        println("=".repeat(80) + "\n")

        val allChunks = mutableListOf<String>()
        val allEvents = mutableListOf<SSEEvent>()
        val startTime = System.currentTimeMillis()

        webTestClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(request))
            .exchange()
            .expectStatus().isOk
            .returnResult(org.springframework.core.io.buffer.DataBuffer::class.java)
            .responseBody
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                val chunk = String(bytes, StandardCharsets.UTF_8)
                allChunks.add(chunk)

                val timestamp = System.currentTimeMillis() - startTime
                println("📦 Chunk #${allChunks.size} (+${timestamp}ms, ${bytes.size} bytes):")
                println("─".repeat(80))

                // 라인별 출력
                val lines = chunk.split("\n")
                for ((lineNum, line) in lines.withIndex()) {
                    when {
                        line.isEmpty() -> println("  [$lineNum] <EMPTY>")
                        line.startsWith("event:") -> {
                            val event = line.substring(6).trim()
                            println("  [$lineNum] EVENT: $event")
                            allEvents.add(SSEEvent("event", event, timestamp))
                        }
                        line.startsWith("data:") -> {
                            val data = line.substring(5).trim()
                            println("  [$lineNum] DATA: $data")
                            allEvents.add(SSEEvent("data", data, timestamp))

                            // JSON 파싱 시도
                            if (data.startsWith("{") && !data.contains("[DONE]")) {
                                try {
                                    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                                    val json = mapper.readTree(data)

                                    // 주요 필드 추출
                                    val fields = mutableMapOf<String, String>()
                                    if (json.has("type")) fields["type"] = json.get("type").asText()
                                    if (json.has("index")) fields["index"] = json.get("index").toString()
                                    if (json.has("delta")) {
                                        val delta = json.get("delta")
                                        if (delta.has("type")) fields["delta.type"] = delta.get("type").asText()
                                        if (delta.has("text")) {
                                            val text = delta.get("text").asText()
                                            fields["delta.text"] = text
                                            println("       → TEXT: '$text'")
                                        }
                                    }
                                    if (json.has("message")) {
                                        val message = json.get("message")
                                        if (message.has("role")) fields["message.role"] = message.get("role").asText()
                                    }
                                } catch (e: Exception) {
                                    println("       ⚠️  JSON parse error: ${e.message}")
                                }
                            }
                        }
                        else -> println("  [$lineNum] OTHER: '$line'")
                    }
                }
                println()

                chunk
            }
            .collectList()
            .block()

        val duration = System.currentTimeMillis() - startTime

        println("=".repeat(80))
        println("📊 SUMMARY")
        println("=".repeat(80))
        println("⏱️  Total duration: ${duration}ms")
        println("📦 Total chunks: ${allChunks.size}")
        println("📋 Total events: ${allEvents.size}")
        println()

        // 이벤트 타입 분석
        println("🎯 EVENT TYPE ANALYSIS:")
        val eventTypes = allEvents.filter { it.type == "event" }
            .groupBy { it.value }
            .mapValues { it.value.size }
        eventTypes.forEach { (type, count) ->
            println("   - $type: $count times")
        }
        println()

        // 전체 응답
        val fullResponse = allChunks.joinToString("")
        println("📄 FULL RESPONSE:")
        println("─".repeat(80))
        println(fullResponse.take(3000))
        if (fullResponse.length > 3000) {
            println("\n... (${fullResponse.length - 3000} more characters)")
        }
        println("─".repeat(80))

        println("\n✅ CAPTURE COMPLETE")
        println("=".repeat(80) + "\n")
    }

    data class SSEEvent(
        val type: String,  // "event" or "data"
        val value: String,
        val timestamp: Long
    )
}
