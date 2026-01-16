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
class ProxySSECaptureTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `capture actual proxy SSE response with real backend`() {
        println("\n" + "=".repeat(80))
        println("🔍 CAPTURING PROXY SSE RESPONSE (WITH REAL BACKEND)")
        println("=".repeat(80) + "\n")

        val request = """
            {
                "model": "claude-haiku-4-5-202501001",
                "max_tokens": 50,
                "messages": [
                    {
                        "role": "user",
                        "content": "Say hi"
                    }
                ]
            }
        """.trimIndent()

        println("📤 Sending request through proxy...")
        println("Request size: ${request.length} bytes (will skip OLLAMA masking)\n")

        val allChunks = mutableListOf<String>()
        val allEvents = mutableListOf<Pair<String, String>>() // (event type, data)

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

                // SSE 파싱
                val lines = chunk.split("\n")
                var currentEvent = ""
                var currentData = ""

                for (line in lines) {
                    when {
                        line.startsWith("event: ") -> {
                            currentEvent = line.substring(7).trim()
                        }
                        line.startsWith("data: ") -> {
                            currentData = line.substring(6).trim()
                            if (currentEvent.isNotEmpty() && currentData.isNotEmpty()) {
                                allEvents.add(Pair(currentEvent, currentData))
                                println("📡 [$currentEvent]")
                                println("   $currentData")
                                println()
                                currentEvent = ""
                                currentData = ""
                            }
                        }
                    }
                }
                chunk
            }
            .collectList()
            .block()

        val fullResponse = allChunks.joinToString("")

        println("=".repeat(80))
        println("📊 ANALYSIS")
        println("=".repeat(80))
        println("Total chunks: ${allChunks.size}")
        println("Total events: ${allEvents.size}")
        println()

        println("🎯 EVENT TYPE DISTRIBUTION:")
        val eventCounts = allEvents.groupingBy { it.first }.eachCount()
        eventCounts.forEach { (type, count) ->
            println("   $type: $count times")
        }
        println()

        println("🔍 SEARCHING FOR KEY EVENTS:")
        val keyEvents = listOf("message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_stop", "ping", "error")
        keyEvents.forEach { event ->
            val found = allEvents.any { it.first == event }
            println("   $event: ${if (found) "✅ Found" else "❌ Not found"}")
        }
        println()

        if (allEvents.isNotEmpty()) {
            println("📄 FIRST 5 EVENTS IN DETAIL:")
            allEvents.take(5).forEachIndexed { index, (event, data) ->
                println("   Event #${index + 1}:")
                println("   Type: $event")
                println("   Data: $data")
                println()

                // content_block_delta 상세 분석
                if (event == "content_block_delta") {
                    try {
                        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                        val json = mapper.readTree(data)
                        println("   📋 Delta Structure:")
                        println("   - type: ${json.get("type")?.asText()}")
                        println("   - index: ${json.get("index")?.asInt()}")
                        if (json.has("delta")) {
                            val delta = json.get("delta")
                            println("   - delta.type: ${delta.get("type")?.asText()}")
                            println("   - delta.text: ${delta.get("text")?.asText()?.take(50)}...")
                        }
                        println()
                    } catch (e: Exception) {
                        println("   (Could not parse as JSON)")
                    }
                }
            }
        }

        println("=".repeat(80))
        println("✅ TEST COMPLETED")
        println("=".repeat(80) + "\n")
    }
}
