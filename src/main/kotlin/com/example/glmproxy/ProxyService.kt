package com.example.glmproxy

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.buffer.DataBuffer
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ClientHttpResponse
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.charset.StandardCharsets
import org.springframework.core.io.buffer.DefaultDataBufferFactory

@Service
class ProxyService(
    @Value("\${target.base-url}") private val targetBaseUrl: String,
    @Value("\${pii.masking.enabled:true}") private val piiMaskingEnabled: Boolean,
    @Value("\${pii.masking.max-size:5000}") private val piiMaskingMaxSize: Int,
    private val webClientBuilder: WebClient.Builder,
    private val piiMaskingService: PIIMaskingService
) {

    private val logger = LoggerFactory.getLogger(ProxyService::class.java)

    fun proxyRequest(exchange: ServerWebExchange): Mono<Void> {
        val request = exchange.request
        val startTime = System.currentTimeMillis()

        // Request 정보 수집
        val path = request.path.pathWithinApplication().value()
        val method = request.method
        val queryParams = request.queryParams
        val headers = request.headers

        // Request Body 읽기
        return DataBufferUtils.join(request.body)
            .flatMap { dataBuffer ->
                val bodyBytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bodyBytes)
                DataBufferUtils.release(dataBuffer)
                val bodyString = String(bodyBytes, StandardCharsets.UTF_8)

                // 요청에서 model 추출
                val requestModel = try {
                    val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(bodyString)
                    json.path("model").asText("claude-sonnet-4-5-20250929")
                } catch (e: Exception) {
                    logger.debug("Failed to extract model from request: {}", e.message)
                    "claude-sonnet-4-5-20250929"
                }

                // 로깅: Request 정보 (원본)
                logger.info("=".repeat(80))
                logger.info("REQUEST INCOMING")
                logger.info("Timestamp: {}", startTime)
                logger.info("Method: {}", method)
                logger.info("Path: {}", path)
                logger.info("Query: {}", queryParams)
                logger.info("Headers:")
                headers.forEach { (key, values) ->
                    logger.info("  $key: $values")
                }
                if (bodyString.isNotEmpty()) {
                    val logBody = if (bodyString.length > 2000) {
                        bodyString.take(2000) + "... (truncated)"
                    } else {
                        bodyString
                    }
                    logger.info("Body (Original): $logBody")
                }
                logger.info("-".repeat(80))

                // PII 마스킹 처리
                val bodySize = bodyString.length
                val shouldUseOllama = piiMaskingEnabled && bodySize <= piiMaskingMaxSize

                // SSE 응답 준비
                val response = exchange.response
                response.headers.contentType = MediaType.parseMediaType("text/event-stream")
                response.headers["Cache-Control"] = "no-cache"
                response.headers["Connection"] = "keep-alive"

                val bufferFactory = response.bufferFactory()

                // ============================================================
                // 표준 Anthropic SSE 이벤트 생성 함수들
                // ============================================================

                /**
                 * 일반 SSE 이벤트 생성 헬퍼 (먼저 정의)
                 */
                fun createSSEEvent(event: String, data: String): DataBuffer {
                    val sseFormat = "event: $event\ndata: $data\n\n"
                    return bufferFactory.wrap(sseFormat.toByteArray(StandardCharsets.UTF_8))
                }

                /**
                 * message_start 이벤트 생성 (실제 API 형식과 동일)
                 */
                fun createMessageStartEvent(messageId: String, role: String, model: String = "claude-sonnet-4-5-20250929"): DataBuffer {
                    // 실제 Anthropic API 형식: content, model, stop_reason, stop_sequence, usage 필드 포함
                    val data = """{"type":"message_start","message":{"id":"$messageId","type":"message","role":"$role","content":[],"model":"$model","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":0,"output_tokens":0}}}"""
                    return createSSEEvent("message_start", data)
                }

                /**
                 * content_block_start 이벤트 생성
                 */
                fun createContentBlockStartEvent(index: Int): DataBuffer {
                    val data = """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}"""
                    return createSSEEvent("content_block_start", data)
                }

                /**
                 * content_block_delta 이벤트 생성 (텍스트 전송)
                 */
                fun createContentBlockDeltaEvent(index: Int, text: String): DataBuffer {
                    val escapedText = text.replace("\n", "\\n").replace("\"", "\\\"")
                    val data = """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"$escapedText"}}"""
                    return createSSEEvent("content_block_delta", data)
                }

                /**
                 * content_block_stop 이벤트 생성
                 */
                fun createContentBlockStopEvent(index: Int): DataBuffer {
                    val data = """{"type":"content_block_stop","index":$index}"""
                    return createSSEEvent("content_block_stop", data)
                }

                /**
                 * message_delta 이벤트 생성
                 */
                fun createMessageDeltaEvent(stopReason: String = "end_turn"): DataBuffer {
                    val data = """{"type":"message_delta","delta":{"stop_reason":"$stopReason"},"usage":{"output_tokens":0}}"""
                    return createSSEEvent("message_delta", data)
                }

                /**
                 * message_stop 이벤트 생성
                 */
                fun createMessageStopEvent(): DataBuffer {
                    val data = """{"type":"message_stop"}"""
                    return createSSEEvent("message_stop", data)
                }

                /**
                 * API Response 이벤트를 content_block으로 변환
                 * - message_start, message_delta, message_stop → content_block_delta로 변환
                 * - content_block_start → content_block_delta로 변환 (새 블록 시작 알림)
                 * - content_block_delta, content_block_stop → 그대로 통과
                 */
                fun transformApiEventToContentBlock(buffer: DataBuffer): DataBuffer {
                    // DataBuffer를 안전하게 byte array로 변환 (heap-based 여부와 무관)
                    val bytes = ByteArray(buffer.readableByteCount())
                    buffer.read(bytes)
                    buffer.readPosition(0) // 읽기 위치 리셋
                    val content = String(bytes, StandardCharsets.UTF_8)
                    val lines = content.split("\n")

                    // event 라인과 data 라인 추출
                    val eventLine = lines.find { it.startsWith("event: ") }
                    val dataLine = lines.find { it.startsWith("data: ") }

                    if (eventLine != null && dataLine != null) {
                        val eventType = eventLine.removePrefix("event: ").trim()
                        val dataContent = dataLine.removePrefix("data: ").trim()

                        return when (eventType) {
                            "message_start" -> {
                                // message_start → content_block_delta로 변환
                                try {
                                    val jsonData = ObjectMapper().readTree(dataContent)
                                    val msgId = jsonData.path("message").path("id").asText("unknown")
                                    val text = "📡 API Response 시작: $msgId\n"
                                    createContentBlockDeltaEvent(2, text)
                                } catch (e: Exception) {
                                    logger.debug("Failed to parse message_start: {}", e.message)
                                    createContentBlockDeltaEvent(2, "📡 API Response 시작\n")
                                }
                            }
                            "content_block_start" -> {
                                // content_block_start → content_block_delta로 변환
                                val text = "📝 API Response 내용:\n"
                                createContentBlockDeltaEvent(2, text)
                            }
                            "message_delta" -> {
                                // message_delta → content_block_delta로 변환
                                try {
                                    val jsonData = ObjectMapper().readTree(dataContent)
                                    val stopReason = jsonData.path("delta").path("stop_reason").asText("unknown")
                                    val outputTokens = jsonData.path("usage").path("output_tokens").asInt(-1)
                                    val text = if (outputTokens > 0) {
                                        "\n📊 API Response 종료: stop_reason=$stopReason, tokens=$outputTokens\n"
                                    } else {
                                        "\n📊 API Response 종료: stop_reason=$stopReason\n"
                                    }
                                    createContentBlockDeltaEvent(2, text)
                                } catch (e: Exception) {
                                    logger.debug("Failed to parse message_delta: {}", e.message)
                                    createContentBlockDeltaEvent(2, "\n📊 API Response 종료\n")
                                }
                            }
                            "message_stop" -> {
                                // message_stop → content_block_delta로 변환
                                val text = "✅ API Response 완료\n"
                                createContentBlockDeltaEvent(2, text)
                            }
                            else -> {
                                // content_block_delta, content_block_stop 이벤트들은 그대로 통과
                                buffer
                            }
                        }
                    }

                    // 파싱 실패시 원본 반환
                    return buffer
                }

                // ============================================================
                // 이벤트 스트림 생성
                // ============================================================
                val eventFlux: Flux<DataBuffer> = if (shouldUseOllama) {
                    val maskingStartTime = System.currentTimeMillis()
                    val messageId = "msg_${System.currentTimeMillis()}_${(0..9999).random()}"

                    logger.info("=".repeat(80))
                    logger.info("🔒 PII MASKING MODE ENABLED")
                    logger.info("=".repeat(80))
                    logger.info("Request size: {} bytes (threshold: {} bytes)", bodySize, piiMaskingMaxSize)
                    logger.info("Message ID: {}", messageId)
                    logger.info("Starting OLLAMA processing...")
                    logger.info("-".repeat(80))

                    // ============================================================
                    // 표준 Anthropic SSE 이벤트 순서로 스트림 구성
                    // ============================================================
                    //
                    // 1. message_start (우리가 보냄)
                    // 2. content_block_start (index: 0, 마스킹 시작)
                    // 3. content_block_delta (index: 0, "🔒 개인정보 마스킹 중...")
                    // 4. content_block_stop (index: 0)
                    //    [OLLAMA 백그라운드 처리]
                    // 5. content_block_start (index: 1, 마스킹 완료)
                    // 6. content_block_delta (index: 1, "✅ 마스킹 완료...")
                    // 7. content_block_stop (index: 1)
                    //    [실제 Anthropic API 응답 스트림]
                    // 8. message_delta
                    // 9. message_stop
                    // ============================================================

                    Mono.defer<Unit> {
                        logger.debug("📡 Client subscribed to SSE stream")
                        logger.debug("📋 Message ID: {}", messageId)
                        Mono.just(Unit)
                    }.flatMapMany {
                        // 1. message_start 이벤트 전송
                        logger.debug("📤 Sending event 1: message_start")
                        Flux.just(createMessageStartEvent(messageId, "assistant", requestModel))
                            .doOnNext { logger.debug("   ✅ message_start sent") }

                            // 2. content_block_start (index: 0) - 마스킹 시작 블록
                            .concatWith(
                                Flux.just(createContentBlockStartEvent(0))
                                    .doOnNext { logger.debug("📤 Sending event 2: content_block_start (index=0 - masking start block)") }
                            )

                            // 3. content_block_delta (index: 0) - 마스킹 시작 메시지
                            .concatWith(
                                Flux.just(createContentBlockDeltaEvent(0, "🔒 개인정보 마스킹 중...\n"))
                                    .doOnNext { logger.debug("📤 Sending event 3: content_block_delta (index=0 - masking start message)") }
                            )

                            // 4. content_block_stop (index: 0)
                            .concatWith(
                                Flux.just(createContentBlockStopEvent(0))
                                    .doOnNext { logger.debug("📤 Sending event 4: content_block_stop (index=0)") }
                            )

                            // 백그라운드에서 OLLAMA 처리 및 다음 이벤트들
                            .concatWith(
                                piiMaskingService.maskJson(bodyString)
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .doOnSubscribe {
                                        logger.debug("🔄 OLLAMA processing started in background")
                                    }
                                    .flatMapMany { maskedBody ->
                                        val maskingDuration = System.currentTimeMillis() - maskingStartTime
                                        val piiMaskingApplied = (maskedBody != bodyString)

                                        logger.info("✅ OLLAMA processing completed")
                                        logger.info("   Duration: {}ms", maskingDuration)
                                        logger.info("   PII Masked: {}", piiMaskingApplied)

                                        if (piiMaskingApplied) {
                                            val maskedLog = if (maskedBody.length > 2000) {
                                                maskedBody.take(2000) + "... (truncated)"
                                            } else {
                                                maskedBody
                                            }
                                            logger.info("Body (Masked): {}", maskedLog)
                                        }

                                        // 5. content_block_start (index: 1) - 마스킹 완료 블록
                                        logger.debug("📤 Sending event 5: content_block_start (index=1 - masking complete block)")

                                        // 6. content_block_delta (index: 1) - 마스킹 완료 메시지
                                        val completeText = "✅ 마스킹 완료 (${maskingDuration}ms)\n\n"
                                        logger.debug("📤 Sending event 6: content_block_delta (index=1 - masking complete message)")

                                        // API 요청 URL 구성
                                        val queryString = if (queryParams.isNotEmpty()) {
                                            queryParams.toSingleValueMap().map { "${it.key}=${it.value}" }.joinToString("&")
                                        } else {
                                            ""
                                        }
                                        val targetUrl = targetBaseUrl + path + if (queryString.isNotEmpty()) "?$queryString" else ""

                                        logger.info("📡 Forwarding to API: {}", targetUrl)

                                        val webClient = webClientBuilder.build()

                                        // 7. content_block_stop (index: 1)
                                        logger.debug("📤 Sending event 7: content_block_stop (index=1)")

                                        // [실제 Anthropic API 응답 스트림]
                                        logger.debug("📡 Starting API response streaming...")

                                        val apiResponseFlux = webClient
                                            .method(method)
                                            .uri(targetUrl)
                                            .headers { targetHeaders ->
                                                request.headers.forEach { (key, values) ->
                                                    when (key.lowercase()) {
                                                        "host" -> {}
                                                        "content-length" -> {}
                                                        "connection" -> {}
                                                        "transfer-encoding" -> {}
                                                        "accept-encoding" -> {}
                                                        else -> targetHeaders[key] = values
                                                    }
                                                }
                                            }
                                            .bodyValue(maskedBody)
                                            .retrieve()
                                            .bodyToFlux(org.springframework.core.io.buffer.DataBuffer::class.java)
                                            .map { buffer -> transformApiEventToContentBlock(buffer) }
                                            .doOnSubscribe {
                                                logger.debug("   ✅ API response subscription started")
                                            }
                                            .doOnNext { buffer ->
                                                logger.trace("   📦 API chunk: {} bytes", buffer.readableByteCount())
                                            }
                                            .doOnComplete {
                                                logger.info("✅ API response streaming completed")
                                                if (piiMaskingApplied) {
                                                    logger.info("🔒 PII MASKING APPLIED: Personal information was masked")
                                                } else {
                                                    logger.info("⚠️  PII MASKING NOT APPLIED: No sensitive data found")
                                                }
                                            }
                                            .doOnError { error ->
                                                logger.error("❌ API response error: {}", error.message)
                                            }

                                        // 이벤트들을 순서대로 결합
                                        Flux.just(createContentBlockStartEvent(1))
                                            .concatWith(Flux.just(createContentBlockDeltaEvent(1, completeText)))
                                            .concatWith(Flux.just(createContentBlockStopEvent(1)))
                                            .concatWith(apiResponseFlux)
                                    }
                                    .onErrorResume { error ->
                                        logger.error("❌ OLLAMA processing failed: {}", error.message)
                                        // 실패 시 원본 데이터로 API 요청
                                        val targetUrl = targetBaseUrl + path
                                        logger.info("📡 Forwarding original to API: {}", targetUrl)

                                        val webClient = webClientBuilder.build()
                                        webClient
                                            .method(method)
                                            .uri(targetUrl)
                                            .headers { targetHeaders ->
                                                request.headers.forEach { (key, values) ->
                                                    when (key.lowercase()) {
                                                        "host" -> {}
                                                        "content-length" -> {}
                                                        "connection" -> {}
                                                        "transfer-encoding" -> {}
                                                        "accept-encoding" -> {}
                                                        else -> targetHeaders[key] = values
                                                    }
                                                }
                                            }
                                            .bodyValue(bodyString)
                                            .retrieve()
                                            .bodyToFlux(org.springframework.core.io.buffer.DataBuffer::class.java)
                                            .map { buffer -> transformApiEventToContentBlock(buffer) }
                                            .doOnSubscribe {
                                                logger.info("✅ Using original data (OLLAMA failed)")
                                            }
                                    }
                            )

                            // 8. message_delta 이벤트
                            .concatWith(
                                Flux.just(createMessageDeltaEvent("end_turn"))
                                    .doOnNext { logger.debug("📤 Sending event 8: message_delta") }
                            )

                            // 9. message_stop 이벤트
                            .concatWith(
                                Flux.just(createMessageStopEvent())
                                    .doOnNext { logger.debug("📤 Sending event 9: message_stop") }
                            )
                            .doOnComplete {
                                val totalDuration = System.currentTimeMillis() - startTime
                                logger.info("=".repeat(80))
                                logger.info("✅ STREAMING COMPLETED")
                                logger.info("Total duration: {}ms", totalDuration)
                                logger.info("=".repeat(80))
                                logger.info("")
                            }
                    }
                    .doOnSubscribe {
                        logger.info("📡 SSE stream subscribed by client")
                    }

                } else {
                    // 마스킹 없이 바로 API 요청 (순수 프록시)
                    if (piiMaskingEnabled && bodySize > piiMaskingMaxSize) {
                        logger.info("⚠️  PII Masking ENABLED but size too large ({} bytes > {} bytes) - Skipping OLLAMA, using original", bodySize, piiMaskingMaxSize)
                    } else {
                        logger.info("⚠️  PII Masking DISABLED - Pure proxy mode")
                    }

                    val queryString = if (queryParams.isNotEmpty()) {
                        queryParams.toSingleValueMap().map { "${it.key}=${it.value}" }.joinToString("&")
                    } else {
                        ""
                    }
                    val targetUrl = targetBaseUrl + path + if (queryString.isNotEmpty()) "?$queryString" else ""

                    logger.info("Forwarding to: $targetUrl")

                    val webClient = webClientBuilder.build()

                    // 순수 API 응답 스트림 반환 (이벤트 변환 적용)
                    webClient
                        .method(method)
                        .uri(targetUrl)
                        .headers { targetHeaders ->
                            request.headers.forEach { (key, values) ->
                                when (key.lowercase()) {
                                    "host" -> {}
                                    "content-length" -> {}
                                    "connection" -> {}
                                    "transfer-encoding" -> {}
                                    "accept-encoding" -> {}
                                    else -> targetHeaders[key] = values
                                }
                            }
                        }
                        .bodyValue(bodyString)
                        .retrieve()
                        .bodyToFlux(org.springframework.core.io.buffer.DataBuffer::class.java)
                        // PII 마스킹 비활성화 시 원본 그대로 전달 (이벤트 변환 안 함)
                        .doOnSubscribe {
                            logger.info("Streaming response from API...")
                        }
                        .doOnNext { buffer ->
                            logger.debug("Forwarding API response buffer ({} bytes)", buffer.readableByteCount())
                        }
                        .doOnComplete {
                            val endTime = System.currentTimeMillis()
                            val duration = endTime - startTime
                            logger.info("✅ Response streaming completed (Duration: {}ms)", duration)
                            if (piiMaskingEnabled && bodySize > piiMaskingMaxSize) {
                                logger.info("⚠️  PII MASKING SKIPPED: Request too large ({} bytes), sent original data", bodySize)
                            } else if (!piiMaskingEnabled) {
                                logger.info("⚠️  PII MASKING DISABLED: Pure proxy mode, original data sent to API")
                            }
                            logger.info("=".repeat(80))
                            logger.info("")
                        }
                }

                // 에러 처리
                val errorHandledFlux = eventFlux
                    .onErrorResume { e ->
                        logger.error("ERROR: {}", e.message, e)
                        logger.info("=".repeat(80))
                        logger.info("")

                        Flux.just(
                            createSSEEvent("error", """{"message":"${e.message}"}"""),
                            bufferFactory.wrap("Internal Server Error".toByteArray(StandardCharsets.UTF_8))
                        )
                    }

                // 응답에 상태 코드 설정 후 스트림 전송
                response.writeWith(errorHandledFlux)
            }
    }

    /**
     * 테스트용 엔드포인트:我们自己가 만든 SSE 이벤트들을 직접 전송
     * Anthropic API를 거치지 않고 더미 응답을 생성
     */
    fun sendTestEvents(exchange: ServerWebExchange): Mono<Void> {
        val response = exchange.response
        val bufferFactory = response.bufferFactory()

        // 요청 바디 읽기 및 로깅
        return exchange.request.body
            .next()
            .map { dataBuffer ->
                val bytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bytes)
                val requestBody = String(bytes, StandardCharsets.UTF_8)
                logger.info("🧪 Test endpoint - Request body: {}", requestBody.take(200))
                requestBody
            }
            .flatMap { requestBody ->
                // 테스트용 더미 응답 생성
                val messageId = "msg_test_${System.currentTimeMillis()}"

                // 요청에서 model 추출
                val model = try {
                    val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody)
                    json.path("model").asText("claude-sonnet-4-5-20250929")
                } catch (e: Exception) {
                    "claude-sonnet-4-5-20250929"
                }

                // SSE 이벤트 생성 함수들 (proxyRequest 내부 함수와 동일)
                fun createSSEEvent(event: String, data: String): DataBuffer {
                    val sseFormat = "event: $event\ndata: $data\n\n"
                    return bufferFactory.wrap(sseFormat.toByteArray(StandardCharsets.UTF_8))
                }

                fun createMessageStartEvent(): DataBuffer {
                    // 실제 Anthropic API 형식과 동일하게 모든 필드 포함
                    val data = """{"type":"message_start","message":{"id":"$messageId","type":"message","role":"assistant","content":[],"model":"$model","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":0,"output_tokens":0}}}"""
                    return createSSEEvent("message_start", data)
                }

                fun createContentBlockStartEvent(index: Int): DataBuffer {
                    val data = """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}"""
                    return createSSEEvent("content_block_start", data)
                }

                fun createContentBlockDeltaEvent(index: Int, text: String): DataBuffer {
                    val escapedText = text.replace("\n", "\\n").replace("\"", "\\\"")
                    val data = """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"$escapedText"}}"""
                    return createSSEEvent("content_block_delta", data)
                }

                fun createContentBlockStopEvent(index: Int): DataBuffer {
                    val data = """{"type":"content_block_stop","index":$index}"""
                    return createSSEEvent("content_block_stop", data)
                }

                fun createMessageDeltaEvent(): DataBuffer {
                    val data = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}"""
                    return createSSEEvent("message_delta", data)
                }

                fun createMessageStopEvent(): DataBuffer {
                    val data = """{"type":"message_stop"}"""
                    return createSSEEvent("message_stop", data)
                }

                // 테스트용 응답 텍스트
                val testResponse = """
                    테스트 응답입니다!

                    이 메시지는我们自己가 만든 SSE 이벤트를 통해 전송됩니다.
                    Anthropic API를 거치지 않고 직접 생성되었습니다.

                    확인할 내용:
                    1. ✅ message_start 이벤트가 전송되었나요?
                    2. ✅ content_block_start 이벤트가 전송되었나요?
                    3. ✅ content_block_delta 이벤트들이 전송되었나요?
                    4. ✅ content_block_stop 이벤트가 전송되었나요?
                    5. ✅ message_delta 이벤트가 전송되었나요?
                    6. ✅ message_stop 이벤트가 전송되었나요?

                    이 모든 이벤트가 올바른 순서로 전송되면 CLAUDE CODE에서 정상적으로 표시됩니다.
                """.trimIndent()

                // 응답 헤더 설정
                response.headers.set("Content-Type", "text/event-stream")
                response.headers.set("Cache-Control", "no-cache")
                response.headers.set("Connection", "keep-alive")

                // 이벤트 스트림 생성
                val eventFlux = Mono.defer<Unit> {
                    logger.info("🧪 Test endpoint called - sending dummy SSE events")
                    Mono.just(Unit)
                }.flatMapMany {
                    // 1. message_start
                    Flux.just(createMessageStartEvent())
                        .doOnNext { logger.debug("📤 Test: message_start sent") }
                        // 2. content_block_start
                        .concatWith(Flux.just(createContentBlockStartEvent(0))
                            .doOnNext { logger.debug("📤 Test: content_block_start sent") })
                        // 3. content_block_delta (여러 번 - 텍스트를 청크로 나누어 전송)
                        .concatWith(Flux.fromArray(testResponse.chunked(50).map { chunk ->
                            createContentBlockDeltaEvent(0, chunk + "\n")
                        }.toTypedArray())
                            .doOnNext { logger.debug("📤 Test: content_block_delta sent") })
                        // 4. content_block_stop
                        .concatWith(Flux.just(createContentBlockStopEvent(0))
                            .doOnNext { logger.debug("📤 Test: content_block_stop sent") })
                        // 5. message_delta
                        .concatWith(Flux.just(createMessageDeltaEvent())
                            .doOnNext { logger.debug("📤 Test: message_delta sent") })
                        // 6. message_stop
                        .concatWith(Flux.just(createMessageStopEvent())
                            .doOnNext { logger.debug("📤 Test: message_stop sent") })
                        .doOnComplete {
                            logger.info("✅ Test: All events sent successfully")
                        }
                }

                response.writeWith(eventFlux)
            }
    }

}
