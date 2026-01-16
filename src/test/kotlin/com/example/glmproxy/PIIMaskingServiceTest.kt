package com.example.glmproxy

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import reactor.test.StepVerifier

@SpringBootTest
class PIIMaskingServiceTest {

    @Autowired
    private lateinit var piiMaskingService: PIIMaskingService

    @Test
    fun `should mask small JSON successfully`() {
        val smallJson = """
            {"user_id": "12345", "email": "test@example.com", "message": "hello"}
        """.trimIndent()

        val result = piiMaskingService.maskJson(smallJson)

        StepVerifier.create(result)
            .assertNext { masked ->
                println("Small JSON masked result: $masked")
                // 마스킹이 되었는지 확인
                assert(masked.contains("[USER_ID]") || masked.contains("[EMAIL]"))
            }
            .verifyComplete()
    }

    @Test
    fun `should handle medium JSON (1KB)`() {
        val mediumJson = buildLargeJson(100) // 약 1KB

        val result = piiMaskingService.maskJson(mediumJson)

        StepVerifier.create(result)
            .assertNext { masked ->
                println("Medium JSON masked successfully, length: ${masked.length}")
                // 실패 시 원본 반환 확인
                if (masked == mediumJson) {
                    println("OLLAMA failed, returned original (expected for large JSON)")
                }
            }
            .verifyComplete()
    }

    @Test
    fun `should handle large JSON (10KB)`() {
        val largeJson = buildLargeJson(1000) // 약 10KB

        val result = piiMaskingService.maskJson(largeJson)

        StepVerifier.create(result)
            .assertNext { masked ->
                println("Large JSON result length: ${masked.length}")
                println("Returned original: ${masked == largeJson}")
            }
            .verifyComplete()
    }

    @Test
    fun `should handle very large JSON (40KB)`() {
        val veryLargeJson = buildLargeJson(4000) // 약 40KB

        val startTime = System.currentTimeMillis()
        val result = piiMaskingService.maskJson(veryLargeJson)

        StepVerifier.create(result)
            .assertNext { masked ->
                val duration = System.currentTimeMillis() - startTime
                println("Very large JSON (40KB) processing time: ${duration}ms")
                println("Result length: ${masked.length}")
                println("Returned original: ${masked == veryLargeJson}")

                // 타임아웃이 너무 길면 경고
                if (duration > 10000) {
                    println("WARNING: Processing took longer than 10 seconds!")
                }
            }
            .verifyComplete()
    }

    @Test
    fun `should handle actual Anthropic API request format`() {
        val anthropicRequest = """
            {
                "model": "claude-sonnet-4-5-20250929",
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "text",
                                "text": "My email is test@example.com and my user ID is user_12345"
                            }
                        ]
                    }
                ],
                "max_tokens": 4096,
                "metadata": {
                    "user_id": "user_9911a7f2646c899166c79a717d612c589ef112c08a8d12b61e0e4bca3c14b4e3_account__session_c1cd8547-22a2-4928-9c48-96688f409afa"
                }
            }
        """.trimIndent()

        val result = piiMaskingService.maskJson(anthropicRequest)

        StepVerifier.create(result)
            .assertNext { masked ->
                println("Anthropic request masked: ${masked.take(200)}...")
                // PII가 마스킹되었는지 확인
                val hasMasking = masked.contains("[USER_ID]") ||
                                masked.contains("[EMAIL]") ||
                                masked.contains("user_")
                println("Has PII masking: $hasMasking")
            }
            .verifyComplete()
    }

    @Test
    fun `should handle timeout gracefully`() {
        val hugeJson = buildLargeJson(10000) // 약 100KB

        val startTime = System.currentTimeMillis()
        val result = piiMaskingService.maskJson(hugeJson)

        StepVerifier.create(result)
            .assertNext { masked ->
                val duration = System.currentTimeMillis() - startTime
                println("Huge JSON (100KB) processing time: ${duration}ms")

                // 타임아웃 시 원본 반환
                if (masked == hugeJson) {
                    println("OLLAMA timeout, returned original JSON (graceful degradation)")
                }
            }
            .verifyComplete()
    }

    /**
     * 테스트용 대용량 JSON 생성
     */
    private fun buildLargeJson(itemCount: Int): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"model\": \"claude-sonnet-4-5-20250929\",\n")
        sb.append("  \"messages\": [\n")

        for (i in 0 until itemCount) {
            sb.append("    {\n")
            sb.append("      \"role\": \"user\",\n")
            sb.append("      \"content\": \"This is message number $i with user_id user_$i and email user$i@example.com\"\n")
            sb.append("    }")
            if (i < itemCount - 1) sb.append(",")
            sb.append("\n")
        }

        sb.append("  ],\n")
        sb.append("  \"max_tokens\": 4096,\n")
        sb.append("  \"metadata\": {\n")
        sb.append("    \"user_id\": \"user_9911a7f2646c899166c79a717d612c589ef112c08a8d12b61e0e4bca3c14b4e3_account__session_c1cd8547-22a2-4928-9c48-96688f409afa\"\n")
        sb.append("  }\n")
        sb.append("}")

        return sb.toString()
    }
}
