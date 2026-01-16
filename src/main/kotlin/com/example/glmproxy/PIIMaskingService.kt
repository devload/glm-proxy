package com.example.glmproxy

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Service
class PIIMaskingService(
    chatClientBuilder: ChatClient.Builder
) {
    private val chatClient = chatClientBuilder.build()
    private val logger = LoggerFactory.getLogger(PIIMaskingService::class.java)

    /**
     * 텍스트에서 개인정보를 마스킹 (Spring AI 사용)
     */
    fun maskText(text: String): Mono<String> {
        val promptText = """
            You are a PII (Personal Identifiable Information) masking system.
            Mask sensitive information while preserving the rest of the text.

            Rules:
            1. Replace email addresses with [EMAIL]
            2. Replace phone numbers with [PHONE]
            3. Replace credit card numbers with [CARD]
            4. Replace SSN/Social Security numbers with [SSN]
            5. Replace IP addresses with [IP]
            6. Replace API keys/tokens with [API_KEY]
            7. Replace file paths with [PATH] (only user-specific parts)
            8. Replace usernames/user IDs with [USER_ID]

            IMPORTANT:
            - Keep the overall text structure intact
            - Only replace the actual sensitive values
            - Respond ONLY with the masked text, no explanations

            Text: $text

            Masked text:
        """.trimIndent()

        logger.debug("Calling OLLAMA to mask PII in text (length: {})", text.length)

        return Mono.fromCallable {
            chatClient
                .prompt()
                .user(promptText)
                .call()
                .content() ?: ""
        }.subscribeOn(Schedulers.boundedElastic())
            .map { maskedText ->
                logger.debug("OLLAMA masking completed")
                maskedText.trim()
            }
            .onErrorResume { e ->
                logger.warn("OLLAMA masking failed, returning original text: {}", e.message)
                Mono.just(text)
            }
    }

    /**
     * JSON 문자열에서 개인정보를 마스킹
     */
    fun maskJson(jsonString: String): Mono<String> {
        val promptText = """
            You are a JSON PII masking system.
            Mask sensitive information in this JSON while preserving valid JSON structure.

            Rules:
            1. Replace "authorization" token values with "[REDACTED_TOKEN]"
            2. Replace "password" values with "[REDACTED_PASSWORD]"
            3. Replace "api_key", "token", "secret" values with "[API_KEY]"
            4. Replace "user_id" values with "[USER_ID]"
            5. Replace "email" values with "[EMAIL]"
            6. Replace "file_path" values with "[PATH]" (keep structure)
            7. Keep all JSON structure, keys, and non-sensitive values unchanged

            IMPORTANT:
            - Respond ONLY with valid JSON
            - Do NOT include markdown code blocks (```)
            - Do NOT include explanations
            - Ensure the output is parseable JSON

            JSON to mask:
            $jsonString

            Masked JSON:
        """.trimIndent()

        logger.debug("Calling OLLAMA to mask PII in JSON (length: {})", jsonString.length)

        return Mono.fromCallable {
            chatClient
                .prompt()
                .user(promptText)
                .call()
                .content() ?: jsonString
        }.subscribeOn(Schedulers.boundedElastic())
            .map { maskedJson ->
                // JSON 코드 블록 제거
                val cleaned = extractJsonFromResponse(maskedJson)
                logger.debug("OLLAMA JSON masking completed")
                cleaned
            }
            .onErrorResume { e ->
                val isTimeout = e.message?.contains("timeout", ignoreCase = true) == true ||
                                e.cause?.message?.contains("timeout", ignoreCase = true) == true

                if (isTimeout) {
                    logger.warn("⏱️ OLLAMA timeout after 30s - sending original data without masking")
                } else {
                    logger.warn("OLLAMA JSON masking failed, returning original JSON: {}", e.message)
                }
                Mono.just(jsonString)
            }
    }

    private fun extractJsonFromResponse(response: String): String {
        var result = response.trim()

        // ```json 또는 ``` 코드 블록 제거
        result = result.replace(Regex("""^```(?:json)?\s*"""), "")
        result = result.replace(Regex("""\s*```$"""), "")

        // 설명 텍스트 제거 (JSON이 아닌 부분)
        val jsonStart = result.indexOf("{")
        val jsonEnd = result.lastIndexOf("}")

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return result.substring(jsonStart, jsonEnd + 1)
        }

        return result
    }
}
