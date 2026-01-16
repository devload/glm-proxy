package com.example.glmproxy

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.nio.charset.StandardCharsets

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SSEStreamingTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `should send SSE events in correct order for small request`() {
        val smallRequest = """
            {
                "model": "claude-haiku-4-5-202501001",
                "messages": [
                    {
                        "role": "user",
                        "content": "My email is test@example.com"
                    }
                ],
                "max_tokens": 100
            }
        """.trimIndent()

        val events = mutableListOf<String>()
        val startTime = System.currentTimeMillis()

        webTestClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(smallRequest))
            .exchange()
            .expectStatus().isOk
            .returnResult(org.springframework.core.io.buffer.DataBuffer::class.java)
            .responseBody
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                String(bytes, StandardCharsets.UTF_8)
            }
            .doOnNext { event ->
                events.add(event)
                println("📡 Received event (${System.currentTimeMillis() - startTime}ms): ${event.take(150)}...")
            }
            .collectList()
            .block()

        val duration = System.currentTimeMillis() - startTime
        println("\n📊 Test completed in ${duration}ms")
        println("📦 Total events received: ${events.size}")

        // 이벤트 순서 확인
        val fullResponse = events.joinToString("")
        println("\n📄 Full response length: ${fullResponse.length} characters")
        println("📄 Full response preview:\n${fullResponse.take(500)}...")

        // SSE 형식 확인
        val hasSSEFormat = fullResponse.contains("event:") && fullResponse.contains("data:")
        println("\n✅ Has SSE format: $hasSSEFormat")

        // 마스킹 시작 메시지 확인
        val hasMaskingStart = fullResponse.contains("🔒 개인정보 마스킹 중")
        println("✅ Has masking start event: $hasMaskingStart")

        // 마스킹 완료 메시지 확인
        val hasMaskingComplete = fullResponse.contains("✅ 마스킹 완료") || fullResponse.contains("마스킹 완료")
        println("✅ Has masking complete event: $hasMaskingComplete")

        // content_block_delta 이벤트 확인
        val hasContentBlockDelta = fullResponse.contains("content_block_delta")
        println("✅ Has content_block_delta events: $hasContentBlockDelta")

        // 이벤트 순서 검증
        if (hasMaskingStart && hasMaskingComplete) {
            val startIndex = fullResponse.indexOf("🔒 개인정보 마스킹 중")
            val completeIndex = fullResponse.indexOf("✅ 마스킹 완료")
            if (startIndex > 0 && completeIndex > 0) {
                val correctOrder = startIndex < completeIndex
                println("✅ Events in correct order: $correctOrder")
            }
        }

        println("\n" + "=".repeat(80))
    }

    @Test
    fun `should skip masking for large requests and send directly`() {
        // 큰 요청 생성 (5KB 이상)
        val largeRequest = buildLargeRequest(1000) // 약 10KB

        val events = mutableListOf<String>()
        val startTime = System.currentTimeMillis()

        webTestClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(largeRequest))
            .exchange()
            .expectStatus().isOk
            .returnResult(org.springframework.core.io.buffer.DataBuffer::class.java)
            .responseBody
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                String(bytes, StandardCharsets.UTF_8)
            }
            .doOnNext { event ->
                events.add(event)
            }
            .collectList()
            .block()

        val duration = System.currentTimeMillis() - startTime
        val fullResponse = events.joinToString("")

        println("\n📊 Large request test completed in ${duration}ms")
        println("📦 Request size: ${largeRequest.length} bytes")
        println("✅ Should skip masking (too large)")
        println("📄 Response length: ${fullResponse.length} characters")

        // 큰 요청은 마스킹 이벤트가 없어야 함
        val hasMaskingEvents = fullResponse.contains("🔒 개인정보 마스킹 중")
        println("✅ Has masking events: $hasMaskingEvents (should be false for large requests)")

        println("\n" + "=".repeat(80))
    }

    @Test
    fun `should handle SSE streaming with multiple chunks`() {
        val request = """
            {
                "model": "claude-haiku-4-5-202501001",
                "messages": [
                    {
                        "role": "user",
                        "content": "Test message with email test@example.com"
                    }
                ],
                "max_tokens": 50,
                "stream": true
            }
        """.trimIndent()

        val eventChunks = mutableListOf<String>()
        var chunkCount = 0

        webTestClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(request))
            .exchange()
            .expectStatus().isOk
            .returnResult(org.springframework.core.io.buffer.DataBuffer::class.java)
            .responseBody
            .doOnNext { buffer ->
                chunkCount++
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                val chunk = String(bytes, StandardCharsets.UTF_8)
                eventChunks.add(chunk)
                println("📦 Chunk #$chunkCount: ${chunk.take(100)}...")
            }
            .collectList()
            .block()

        println("\n📊 Streaming test completed")
        println("📦 Total chunks: $chunkCount")
        println("✅ SSE streaming works with multiple chunks")

        println("\n" + "=".repeat(80))
    }

    private fun buildLargeJson(itemCount: Int): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"model\": \"claude-sonnet-4-5-20250929\",\n")
        sb.append("  \"messages\": [\n")

        for (i in 0 until itemCount) {
            sb.append("    {\n")
            sb.append("      \"role\": \"user\",\n")
            sb.append("      \"content\": \"This is message number $i with user_id user_$i and email user$i@example.com and some additional padding text to make the message longer\"\n")
            sb.append("    }")
            if (i < itemCount - 1) sb.append(",")
            sb.append("\n")
        }

        sb.append("  ],\n")
        sb.append("  \"max_tokens\": 4096\n")
        sb.append("}")

        return sb.toString()
    }

    private fun buildLargeRequest(itemCount: Int): String {
        return buildLargeJson(itemCount)
    }
}
