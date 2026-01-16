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
class SimpleSSETest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `SSE streaming test - verify event order`() {
        println("\n" + "=".repeat(80))
        println("🧪 SSE STREAMING TEST STARTED")
        println("=".repeat(80) + "\n")

        val smallRequest = """
            {
                "model": "claude-haiku-4-5-202501001",
                "messages": [
                    {
                        "role": "user",
                        "content": "My email is test@example.com"
                    }
                ],
                "max_tokens": 50
            }
        """.trimIndent()

        println("📤 Sending request with PII (email)...")
        println("Request size: ${smallRequest.length} bytes\n")

        val responseChunks = mutableListOf<String>()
        var firstChunkTime = 0L
        var lastChunkTime = 0L

        val result = webTestClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(smallRequest))
            .exchange()
            .expectStatus().isOk
            .returnResult(org.springframework.core.io.buffer.DataBuffer::class.java)
            .responseBody
            .map { buffer ->
                val now = System.currentTimeMillis()
                if (firstChunkTime == 0L) firstChunkTime = now
                lastChunkTime = now

                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                val chunk = String(bytes, StandardCharsets.UTF_8)
                responseChunks.add(chunk)

                println("📦 [${now - firstChunkTime}ms] Received chunk (${chunk.length} chars):")
                println("   ${chunk.take(150)}${if (chunk.length > 150) "..." else ""}")
                println()
                chunk
            }
            .collectList()
            .block()

        val fullResponse = responseChunks.joinToString("")
        val totalTime = lastChunkTime - firstChunkTime

        println("\n" + "=".repeat(80))
        println("📊 TEST RESULTS")
        println("=".repeat(80))
        println("⏱️  Total time: ${totalTime}ms")
        println("📦 Total chunks: ${responseChunks.size}")
        println("📄 Total response size: ${fullResponse.length} characters\n")

        // SSE 형식 확인
        println("✅ SSE FORMAT CHECKS:")
        println("   - Has 'event:' keyword: ${fullResponse.contains("event:")}")
        println("   - Has 'data:' keyword: ${fullResponse.contains("data:")}")
        println("   - Has 'content_block_delta': ${fullResponse.contains("content_block_delta")}\n")

        // 이벤트 순서 확인
        println("🎯 EVENT ORDER CHECKS:")
        val maskingStart = fullResponse.indexOf("🔒 개인정보 마스킹 중")
        val maskingComplete = fullResponse.indexOf("✅ 마스킹 완료")

        println("   - Masking start event: ${if (maskingStart >= 0) "✅ Found at position $maskingStart" else "❌ Not found"}")
        println("   - Masking complete event: ${if (maskingComplete >= 0) "✅ Found at position $maskingComplete" else "❌ Not found"}")

        if (maskingStart >= 0 && maskingComplete >= 0) {
            val correctOrder = maskingStart < maskingComplete
            println("   - Events in correct order: ${if (correctOrder) "✅ YES" else "❌ NO"}")
        }
        println()

        // PII 마스킹 확인
        println("🔒 PII MASKING CHECKS:")
        println("   - Original email 'test@example.com' present: ${fullResponse.contains("test@example.com")}")
        println("   - Masked as '[EMAIL]': ${fullResponse.contains("[EMAIL]")}\n")

        // 응답 미리보기
        println("📄 RESPONSE PREVIEW (first 500 chars):")
        println("   " + fullResponse.take(500).replace("\n", "\n   "))
        println("\n" + "=".repeat(80))
        println("🧪 TEST COMPLETED")
        println("=".repeat(80) + "\n")
    }
}
