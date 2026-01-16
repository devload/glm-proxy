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
 * 실제 API 응답 vs 프록시 응답 상세 비교
 *
 * 목표:
 * 1. 실제 Anthropic API 응답 캡처 (헤더 + 바디)
 * 2. 프록시를 통한 응답 캡처 (헤더 + 바디)
 * 3. 두 응답 비교 분석
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CompareResponsesTest {

    @Value("\${target.base-url}")
    private lateinit var targetBaseUrl: String

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `compare real API response headers and body with proxy response`() {
        val testRequest = """
            {
                "model": "claude-haiku-4-5-202501001",
                "messages": [
                    {
                        "role": "user",
                        "content": "Hello, please say hi"
                    }
                ],
                "max_tokens": 50
            }
        """.trimIndent()

        println("\n" + "=".repeat(80))
        println("🔍 RESPONSE COMPARISON TEST")
        println("=".repeat(80))
        println("📡 Target API: $targetBaseUrl")
        println("📤 Request size: ${testRequest.length} bytes")
        println("=".repeat(80) + "\n")

        // ============================================================
        // 1. 실제 API 직접 호출 (프록시 없이)
        // ============================================================
        println("\n" + "🔷".repeat(40))
        println("1️⃣  CALLING REAL API DIRECTLY")
        println("🔷".repeat(40) + "\n")

        val directWebClient = org.springframework.web.reactive.function.client.WebClient.builder()
            .baseUrl(targetBaseUrl)
            .build()

        val directResponseHeaders = mutableMapOf<String, String>()
        val directResponseChunks = mutableListOf<String>()
        var directFirstByteTime = 0L
        var directStartTime = System.currentTimeMillis()

        try {
            directWebClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", "test-key") // 테스트용
                .body(BodyInserters.fromValue(testRequest))
                .exchangeToFlux { response ->
                    // 헤더 캡처
                    val httpHeaders = response.headers().asHttpHeaders()
                    httpHeaders.forEach { key, values ->
                        directResponseHeaders[key] = values.joinToString(", ")
                    }

                    directFirstByteTime = System.currentTimeMillis() - directStartTime

                    println("📊 Direct API Response:")
                    println("   Status: ${response.statusCode()}")
                    println("   Headers:")
                    directResponseHeaders.forEach { (key, value) ->
                        println("     $key: $value")
                    }
                    println()

                    // 바디 스트리밍
                    response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer::class.java)
                        .map { buffer ->
                            val bytes = ByteArray(buffer.readableByteCount())
                            buffer.read(bytes)
                            val chunk = String(bytes, StandardCharsets.UTF_8)
                            directResponseChunks.add(chunk)

                            // 첫 번째 청크 로그
                            if (directResponseChunks.size == 1) {
                                println("📦 First chunk received at ${directFirstByteTime}ms:")
                                println("   " + chunk.take(200).replace("\n", "\\n"))
                                if (chunk.length > 200) println("   ...")
                            }
                            buffer
                        }
                }
                .collectList()
                .block()

            val directDuration = System.currentTimeMillis() - directStartTime
            println("\n✅ Direct API completed in ${directDuration}ms")
            println("   Total chunks: ${directResponseChunks.size}")
            println("   Total bytes: ${directResponseChunks.joinToString("").toByteArray().size}")

        } catch (e: Exception) {
            println("❌ Direct API failed: ${e.message}")
            println("   (Expected if no valid API key)")
        }

        // ============================================================
        // 2. 프록시를 통한 호출
        // ============================================================
        println("\n" + "🔷".repeat(40))
        println("2️⃣  CALLING THROUGH PROXY")
        println("🔷".repeat(40) + "\n")

        val proxyResponseHeaders = mutableMapOf<String, String>()
        val proxyResponseChunks = mutableListOf<String>()
        var proxyFirstByteTime = 0L
        var proxyStartTime = System.currentTimeMillis()

        val result = webTestClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(testRequest))
            .exchange()
            .expectStatus().isOk
            .returnResult(org.springframework.core.io.buffer.DataBuffer::class.java)

        // 헤더 캡처
        result.responseHeaders.forEach { key, values ->
            proxyResponseHeaders[key] = values.joinToString(", ")
        }

        proxyFirstByteTime = System.currentTimeMillis() - proxyStartTime

        println("📊 Proxy Response:")
        println("   Status: ${result.status}")
        println("   Headers:")
        proxyResponseHeaders.forEach { (key, value) ->
            println("     $key: $value")
        }
        println()

        result.getResponseBody()
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                val chunk = String(bytes, StandardCharsets.UTF_8)
                proxyResponseChunks.add(chunk)

                // 첫 번째 청크 로그
                if (proxyResponseChunks.size == 1) {
                    println("📦 First chunk received at ${proxyFirstByteTime}ms:")
                    println("   " + chunk.take(200).replace("\n", "\\n"))
                    if (chunk.length > 200) println("   ...")
                }

                chunk
            }
            .collectList()
            .block()

        val proxyDuration = System.currentTimeMillis() - proxyStartTime
        println("\n✅ Proxy completed in ${proxyDuration}ms")
        println("   Total chunks: ${proxyResponseChunks.size}")
        println("   Total bytes: ${proxyResponseChunks.joinToString("").toByteArray().size}")

        // ============================================================
        // 3. 상세 비교 분석
        // ============================================================
        println("\n" + "🔷".repeat(40))
        println("3️⃣  DETAILED COMPARISON")
        println("🔷".repeat(40) + "\n")

        val directFullResponse = directResponseChunks.joinToString("")
        val proxyFullResponse = proxyResponseChunks.joinToString("")

        // 헤더 비교
        println("📋 HEADER COMPARISON:")
        println("─".repeat(80))

        val allHeaderKeys = mutableSetOf<String>()
        allHeaderKeys.addAll(directResponseHeaders.keys)
        allHeaderKeys.addAll(proxyResponseHeaders.keys)

        allHeaderKeys.forEach { key ->
            val directValue = directResponseHeaders[key]
            val proxyValue = proxyResponseHeaders[key]

            when {
                directValue == null && proxyValue == null -> {}
                directValue == null -> println("   ❌ $key: (missing in direct) = '$proxyValue'")
                proxyValue == null -> println("   ❌ $key: '$directValue' = (missing in proxy)")
                directValue == proxyValue -> println("   ✅ $key: '$directValue'")
                else -> {
                    println("   ⚠️  $key:")
                    println("       Direct:  '$directValue'")
                    println("       Proxy:   '$proxyValue'")
                }
            }
        }

        // 바디 비교 - SSE 이벤트 파싱
        println("\n📄 BODY COMPARISON:")
        println("─".repeat(80))

        fun parseSSEEvents(response: String): List<Map<String, String>> {
            val events = mutableListOf<Map<String, String>>()
            val lines = response.split("\n")
            var currentEvent = mutableMapOf<String, String>()

            for (line in lines) {
                when {
                    line.startsWith("event:") -> {
                        currentEvent["event"] = line.substring(6).trim()
                    }
                    line.startsWith("data:") -> {
                        currentEvent["data"] = line.substring(5).trim()
                        if (currentEvent["event"] != null) {
                            events.add(currentEvent.toMap())
                            currentEvent = mutableMapOf()
                        }
                    }
                }
            }
            return events
        }

        val directEvents = if (directFullResponse.isNotEmpty()) parseSSEEvents(directFullResponse) else emptyList()
        val proxyEvents = parseSSEEvents(proxyFullResponse)

        println("   Direct API events: ${directEvents.size}")
        println("   Proxy events: ${proxyEvents.size}")
        println()

        if (directEvents.isNotEmpty()) {
            println("   📋 Direct API event types:")
            directEvents.groupBy { it["event"] }.forEach { (type, count) ->
                println("      - $type: ${count.size} events")
            }

            println("\n   📋 First 3 direct events:")
            directEvents.take(3).forEachIndexed { index, event ->
                println("      Event #${index + 1}:")
                println("         type: ${event["event"]}")
                println("         data: ${event["data"]?.take(100)}...")
            }
        }

        println("\n   📋 Proxy event types:")
        proxyEvents.groupBy { it["event"] }.forEach { (type, count) ->
            println("      - $type: ${count.size} events")
        }

        println("\n   📋 First 5 proxy events:")
        proxyEvents.take(5).forEachIndexed { index, event ->
            println("      Event #${index + 1}:")
            println("         type: ${event["event"]}")
            println("         data: ${event["data"]?.take(100)}...")

            // content_block_delta 상세 분석
            if (event["event"] == "content_block_delta") {
                try {
                    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                    val json = mapper.readTree(event["data"])
                    if (json.has("delta") && json["delta"].has("text")) {
                        val text = json["delta"]["text"].asText()
                        println("         → delta.text: '$text'")
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }
        }

        // 커스텀 이벤트 확인
        println("\n🔍 CUSTOM EVENTS CHECK:")
        println("─".repeat(80))
        val hasMaskingStart = proxyFullResponse.contains("🔒 개인정보 마스킹 중")
        val hasMaskingComplete = proxyFullResponse.contains("✅ 마스킹 완료")
        println("   Start event (🔒): $hasMaskingStart")
        println("   Complete event (✅): $hasMaskingComplete")

        println("\n" + "=".repeat(80))
        println("✅ COMPARISON COMPLETE")
        println("=".repeat(80) + "\n")
    }
}
