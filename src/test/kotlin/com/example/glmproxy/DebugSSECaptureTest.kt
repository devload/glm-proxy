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
 * SSE 이벤트를 RAW 형태로 캡처해서 실제로 전송되는 바이트를 확인
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DebugSSECaptureTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `capture raw SSE bytes to see exact format`() {
        val request = """
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

        println("\n" + "=".repeat(80))
        println("🔍 RAW SSE CAPTURE TEST")
        println("=".repeat(80))

        val allBytes = mutableListOf<Byte>()
        val allChunks = mutableListOf<String>()
        var firstChunkTime = 0L
        var chunkCount = 0

        webTestClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(request))
            .exchange()
            .expectStatus().isOk
            .returnResult(org.springframework.core.io.buffer.DataBuffer::class.java)
            .responseBody
            .doOnSubscribe {
                firstChunkTime = System.currentTimeMillis()
                println("⏱️  Stream subscribed at: $firstChunkTime")
            }
            .map { buffer ->
                chunkCount++
                val currentTime = System.currentTimeMillis() - firstChunkTime

                // 원본 바이트 읽기
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)

                // 바이트 수집
                bytes.forEach { allBytes.add(it) }

                // 문자열 변환
                val chunk = String(bytes, StandardCharsets.UTF_8)
                allChunks.add(chunk)

                println("\n" + "-".repeat(80))
                println("📦 CHUNK #$chunkCount (at ${currentTime}ms, ${bytes.size} bytes)")
                println("-".repeat(80))

                // 각 라인 분석
                val lines = chunk.split("\n")
                for ((index, line) in lines.withIndex()) {
                    val lineNum = index + 1
                    when {
                        line.isEmpty() -> println("  [$lineNum] <EMPTY LINE>")
                        line.startsWith("event:") -> {
                            val eventContent = line.substring(6).trim()
                            println("  [$lineNum] EVENT LINE: '$eventContent'")
                            println("       Raw bytes: ${line.toByteArray(StandardCharsets.UTF_8).toList()}")
                        }
                        line.startsWith("data:") -> {
                            val dataContent = line.substring(5).trim()
                            println("  [$lineNum] DATA LINE: '$dataContent'")

                            // JSON 데이터라면 파싱 시도
                            if (dataContent.startsWith("{")) {
                                try {
                                    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                                    val json = mapper.readTree(dataContent)

                                    // 주요 필드 추출
                                    if (json.has("type")) {
                                        println("       → type: ${json.get("type").asText()}")
                                    }
                                    if (json.has("delta")) {
                                        val delta = json.get("delta")
                                        if (delta.has("type")) {
                                            println("       → delta.type: ${delta.get("type").asText()}")
                                        }
                                        if (delta.has("text")) {
                                            val text = delta.get("text").asText()
                                            println("       → delta.text: '$text'")
                                        }
                                    }
                                    if (json.has("index")) {
                                        println("       → index: ${json.get("index").asInt()}")
                                    }
                                } catch (e: Exception) {
                                    println("       ⚠️  JSON parse failed: ${e.message}")
                                }
                            }
                        }
                        else -> {
                            println("  [$lineNum] OTHER: '$line'")
                        }
                    }
                }

                chunk
            }
            .collectList()
            .block()

        println("\n" + "=".repeat(80))
        println("📊 SUMMARY")
        println("=".repeat(80))
        println("📦 Total chunks: $chunkCount")
        println("📦 Total bytes: ${allBytes.size}")
        println("📦 Total characters: ${allChunks.joinToString("").length}")
        println()

        // 전체 응답을 처음 2000 문자만 출력
        val fullResponse = allChunks.joinToString("")
        println("📄 FULL RESPONSE (first 2000 chars):")
        println("─".repeat(80))
        println(fullResponse.take(2000))
        if (fullResponse.length > 2000) {
            println("\n... (${fullResponse.length - 2000} more characters)")
        }
        println("─".repeat(80))

        // 이벤트 유형 분석
        println("\n🎯 EVENT TYPE ANALYSIS:")
        val eventLines = fullResponse.split("\n").filter { it.startsWith("event:") }
        val eventTypes = eventLines.map { it.substring(6).trim() }.groupBy { it }.mapValues { it.value.size }
        eventTypes.forEach { (type, count) ->
            println("   - '$type': $count times")
        }

        // 데이터 라인 분석
        println("\n📋 DATA LINE ANALYSIS:")
        val dataLines = fullResponse.split("\n").filter { it.startsWith("data:") }
        println("   Total data lines: ${dataLines.size}")

        val contentBlockDeltas = dataLines.filter { it.contains("content_block_delta") }
        println("   content_block_delta: ${contentBlockDeltas.size}")

        val maskingStart = fullResponse.contains("🔒 개인정보 마스킹 중")
        val maskingComplete = fullResponse.contains("✅ 마스킹 완료")

        println("\n🔍 CUSTOM EVENTS:")
        println("   - Start event (🔒): $maskingStart")
        println("   - Complete event (✅): $maskingComplete")

        println("\n" + "=".repeat(80))
        println("✅ CAPTURE COMPLETE")
        println("=".repeat(80) + "\n")
    }
}
