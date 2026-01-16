package com.example.glmproxy

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.nio.charset.StandardCharsets

/**
 * Reactive SSE 스트리밍 로직 검증 테스트
 *
 * 목표:
 * 1. 시작 이벤트가 즉시 전송되는지 확인
 * 2. 이벤트 순서가 올바른지 확인 (시작 -> 마스킹 완료 -> API 응답)
 * 3. 각 이벤트가 올바른 SSE 형식인지 확인
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ReactiveSSETest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `test event ordering - start event should come first immediately`() {
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

        val timestamps = mutableListOf<Pair<String, Long>>()
        val events = mutableListOf<String>()
        val testStartTime = System.currentTimeMillis()

        println("\n" + "=".repeat(80))
        println("🧪 TEST: Event Ordering with Immediate Start Event")
        println("=".repeat(80))
        println("⏱️  Test started at: $testStartTime")
        println("📤 Sending request (${smallRequest.length} bytes)...\n")

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
                val chunk = String(bytes, StandardCharsets.UTF_8)

                // 이벤트 파싱
                val lines = chunk.split("\n")
                for (line in lines) {
                    if (line.startsWith("data:")) {
                        val data = line.substring(5).trim()
                        val timestamp = System.currentTimeMillis() - testStartTime

                        if (data.isNotEmpty() && data != "[DONE]") {
                            timestamps.add(Pair(data, timestamp))
                            events.add(data)

                            // 첫 번째 이벤트 수신 시간 기록
                            if (events.size == 1) {
                                println("🚨 FIRST EVENT received at ${timestamp}ms")
                            }

                            // 이벤트 내용에 따라 로그
                            when {
                                data.contains("🔒 개인정보 마스킹 중") -> {
                                    println("✅ Start event received at ${timestamp}ms: $data")
                                }
                                data.contains("✅ 마스킹 완료") -> {
                                    println("✅ Complete event received at ${timestamp}ms")
                                }
                                data.contains("type") && data.contains("delta") -> {
                                    println("📡 API delta event received at ${timestamp}ms")
                                }
                            }
                        }
                    }
                }
                chunk
            }
            .collectList()
            .block()

        val totalDuration = System.currentTimeMillis() - testStartTime

        println("\n" + "=".repeat(80))
        println("📊 TEST RESULTS")
        println("=".repeat(80))
        println("⏱️  Total duration: ${totalDuration}ms")
        println("📦 Total events received: ${events.size}")
        println()

        // 검증 1: 시작 이벤트 존재 확인
        val hasStartEvent = events.any { it.contains("🔒 개인정보 마스킹 중") }
        println("1. Start event exists: $hasStartEvent")
        assertTrue(hasStartEvent, "Start event should be present")

        // 검증 2: 완료 이벤트 존재 확인
        val hasCompleteEvent = events.any { it.contains("✅ 마스킹 완료") }
        println("2. Complete event exists: $hasCompleteEvent")
        assertTrue(hasCompleteEvent, "Complete event should be present")

        // 검증 3: 이벤트 순서 확인
        if (hasStartEvent && hasCompleteEvent) {
            val startEventIndex = events.indexOfFirst { it.contains("🔒 개인정보 마스킹 중") }
            val completeEventIndex = events.indexOfFirst { it.contains("✅ 마스킹 완료") }

            val correctOrder = startEventIndex < completeEventIndex
            println("3. Events in correct order: $correctOrder (start at $startEventIndex, complete at $completeEventIndex)")
            assertTrue(correctOrder, "Start event should come before complete event")

            // 시작 이벤트가 빨리 도착했는지 확인 (3초 이내)
            val startEventTime = timestamps[startEventIndex].second
            val startEventImmediate = startEventTime < 3000
            println("4. Start event immediate (< 3s): $startEventImmediate (${startEventTime}ms)")
            assertTrue(startEventImmediate, "Start event should be received within 3 seconds")
        }

        // 검증 4: SSE 형식 확인
        val fullResponse = events.joinToString("\n")
        val hasSSFormat = fullResponse.contains("content_block_delta")
        println("5. Has SSE format with content_block_delta: $hasSSFormat")
        assertTrue(hasSSFormat, "Response should have proper SSE format")

        println("\n" + "=".repeat(80))
        println("✅ ALL TESTS PASSED")
        println("=".repeat(80) + "\n")
    }

    @Test
    fun `test SSE event format is valid`() {
        val request = """
            {
                "model": "claude-haiku-4-5-202501001",
                "messages": [
                    {
                        "role": "user",
                        "content": "test@example.com"
                    }
                ],
                "max_tokens": 50
            }
        """.trimIndent()

        println("\n" + "=".repeat(80))
        println("🧪 TEST: SSE Event Format Validation")
        println("=".repeat(80) + "\n")

        val rawEvents = mutableListOf<String>()

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
                rawEvents.add(chunk)
                chunk
            }
            .collectList()
            .block()

        val fullResponse = rawEvents.joinToString("")

        println("📄 Response length: ${fullResponse.length} characters")
        println()

        // SSE 형식 검증
        val lines = fullResponse.split("\n")
        var eventCount = 0
        var dataCount = 0
        var hasValidFormat = false

        for (line in lines) {
            when {
                line.startsWith("event:") -> {
                    eventCount++
                    val eventType = line.substring(6).trim()
                    println("📋 Event #$eventCount: type='$eventType'")
                }
                line.startsWith("data:") -> {
                    dataCount++
                    val data = line.substring(5).trim()
                    if (data.isNotEmpty() && data != "[DONE]") {
                        println("   Data #$dataCount: ${data.take(80)}...")

                        // JSON 형식 검증
                        if (data.startsWith("{") && data.endsWith("}")) {
                            hasValidFormat = true
                        }
                    }
                }
            }
        }

        println()
        println("📊 Format Statistics:")
        println("   - Total event lines: $eventCount")
        println("   - Total data lines: $dataCount")
        println("   - Has valid JSON format: $hasValidFormat")

        println("\n" + "=".repeat(80))
        assertTrue(eventCount > 0, "Should have event lines")
        assertTrue(dataCount > 0, "Should have data lines")
        assertTrue(hasValidFormat, "Should have valid JSON format")
        println("✅ SSE FORMAT VALIDATION PASSED")
        println("=".repeat(80) + "\n")
    }
}
