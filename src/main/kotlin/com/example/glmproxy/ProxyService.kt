package com.example.glmproxy

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
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
    private val piiMaskingService: PIIMaskingService,
    private val tracer: Tracer
) {

    private val logger = LoggerFactory.getLogger(ProxyService::class.java)
    private val objectMapper = ObjectMapper()

    fun proxyRequest(exchange: ServerWebExchange): Mono<Void> {
        val request = exchange.request
        val startTime = System.currentTimeMillis()

        // OpenTelemetry Span 생성
        val parentSpan = tracer.nextSpan()
            .name("proxy_request")
            .tag("http.method", request.method.name())
            .tag("http.url", request.path.value())
            .tag("component", "glm-proxy")
            .tag("proxy.type", "anthropic-api")

        parentSpan.start()

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
                    val json = objectMapper.readTree(bodyString)
                    json.path("model").asText("claude-sonnet-4-5-20250929")
                } catch (e: Exception) {
                    logger.debug("Failed to extract model from request: {}", e.message)
                    "claude-sonnet-4-5-20250929"
                }

                // Span에 Request 정보 기록
                parentSpan.tag("http.path", path)
                parentSpan.tag("http.query", queryParams.toString())
                parentSpan.tag("request.model", requestModel)
                parentSpan.tag("request.body_size", bodyString.length.toString())

                // Request body를 span event로 기록 (truncated)
                val truncatedRequestBody = if (bodyString.length > 1000) {
                    bodyString.take(1000) + "...(truncated)"
                } else {
                    bodyString
                }
                parentSpan.event("request_received")

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

                parentSpan.tag("pii.masking.enabled", piiMaskingEnabled.toString())
                parentSpan.tag("pii.masking.should_process", shouldUseOllama.toString())

                // SSE 응답 준비
                val response = exchange.response
                response.headers.contentType = MediaType.parseMediaType("text/event-stream")
                response.headers["Cache-Control"] = "no-cache"
                response.headers["Connection"] = "keep-alive"

                val bufferFactory = response.bufferFactory()

                // ============================================================
                // 표준 Anthropic SSE 이벤트 생성 함수들
                // ============================================================

                fun createSSEEvent(event: String, data: String): DataBuffer {
                    val sseFormat = "event: $event\ndata: $data\n\n"
                    return bufferFactory.wrap(sseFormat.toByteArray(StandardCharsets.UTF_8))
                }

                fun createMessageStartEvent(messageId: String, role: String, model: String = "claude-sonnet-4-5-20250929"): DataBuffer {
                    val data = """{"type":"message_start","message":{"id":"$messageId","type":"message","role":"$role","content":[],"model":"$model","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":0,"output_tokens":0}}}"""
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

                fun createMessageDeltaEvent(stopReason: String = "end_turn"): DataBuffer {
                    val data = """{"type":"message_delta","delta":{"stop_reason":"$stopReason"},"usage":{"output_tokens":0}}"""
                    return createSSEEvent("message_delta", data)
                }

                fun createMessageStopEvent(): DataBuffer {
                    val data = """{"type":"message_stop"}"""
                    return createSSEEvent("message_stop", data)
                }

                // Response 데이터를 수집하기 위한 변수들
                val responseCollector = StringBuilder()
                var totalResponseBytes = 0

                fun transformApiEventToContentBlock(buffer: DataBuffer): DataBuffer {
                    val bytes = ByteArray(buffer.readableByteCount())
                    buffer.read(bytes)
                    buffer.readPosition(0)
                    val content = String(bytes, StandardCharsets.UTF_8)

                    // Response 데이터 수집 (truncated)
                    totalResponseBytes += bytes.size
                    if (responseCollector.length < 2000) {
                        responseCollector.append(content)
                    }

                    val lines = content.split("\n")
                    val eventLine = lines.find { it.startsWith("event: ") }
                    val dataLine = lines.find { it.startsWith("data: ") }

                    if (eventLine != null && dataLine != null) {
                        val eventType = eventLine.removePrefix("event: ").trim()
                        val dataContent = dataLine.removePrefix("data: ").trim()

                        // Span event로 각 SSE 이벤트 기록
                        parentSpan.event("sse_event: $eventType")

                        return when (eventType) {
                            "message_start" -> {
                                try {
                                    val jsonData = objectMapper.readTree(dataContent)
                                    val msgId = jsonData.path("message").path("id").asText("unknown")
                                    parentSpan.tag("response.message_id", msgId)
                                    val text = "API Response: $msgId\n"
                                    createContentBlockDeltaEvent(2, text)
                                } catch (e: Exception) {
                                    logger.debug("Failed to parse message_start: {}", e.message)
                                    createContentBlockDeltaEvent(2, "API Response\n")
                                }
                            }
                            "content_block_start" -> {
                                val text = "API Response:\n"
                                createContentBlockDeltaEvent(2, text)
                            }
                            "message_delta" -> {
                                try {
                                    val jsonData = objectMapper.readTree(dataContent)
                                    val stopReason = jsonData.path("delta").path("stop_reason").asText("unknown")
                                    val outputTokens = jsonData.path("usage").path("output_tokens").asInt(-1)
                                    parentSpan.tag("response.stop_reason", stopReason)
                                    if (outputTokens > 0) {
                                        parentSpan.tag("response.output_tokens", outputTokens.toString())
                                    }
                                    val text = if (outputTokens > 0) {
                                        "\nAPI Response: stop_reason=$stopReason, tokens=$outputTokens\n"
                                    } else {
                                        "\nAPI Response: stop_reason=$stopReason\n"
                                    }
                                    createContentBlockDeltaEvent(2, text)
                                } catch (e: Exception) {
                                    logger.debug("Failed to parse message_delta: {}", e.message)
                                    createContentBlockDeltaEvent(2, "\nAPI Response end\n")
                                }
                            }
                            "message_stop" -> {
                                val text = "API Response complete\n"
                                createContentBlockDeltaEvent(2, text)
                            }
                            else -> {
                                buffer
                            }
                        }
                    }

                    return buffer
                }

                // ============================================================
                // 이벤트 스트림 생성
                // ============================================================
                val eventFlux: Flux<DataBuffer> = if (shouldUseOllama) {
                    val maskingStartTime = System.currentTimeMillis()
                    val messageId = "msg_${System.currentTimeMillis()}_${(0..9999).random()}"

                    // PII Masking Span 생성
                    val maskingSpan = tracer.nextSpan(parentSpan)
                        .name("pii_masking")
                        .tag("masking.enabled", "true")
                        .tag("masking.body_size", bodySize.toString())

                    maskingSpan.start()

                    logger.info("=".repeat(80))
                    logger.info("PII MASKING MODE ENABLED")
                    logger.info("=".repeat(80))
                    logger.info("Request size: {} bytes (threshold: {} bytes)", bodySize, piiMaskingMaxSize)
                    logger.info("Message ID: {}", messageId)
                    logger.info("Starting OLLAMA processing...")
                    logger.info("-".repeat(80))

                    Mono.defer<Unit> {
                        logger.debug("Client subscribed to SSE stream")
                        logger.debug("Message ID: {}", messageId)
                        Mono.just(Unit)
                    }.flatMapMany {
                        Flux.just(createMessageStartEvent(messageId, "assistant", requestModel))
                            .doOnNext { logger.debug("Sending event 1: message_start") }
                            .concatWith(
                                Flux.just(createContentBlockStartEvent(0))
                                    .doOnNext { logger.debug("Sending event 2: content_block_start (index=0)") }
                            )
                            .concatWith(
                                Flux.just(createContentBlockDeltaEvent(0, "PII masking in progress...\n"))
                                    .doOnNext { logger.debug("Sending event 3: content_block_delta (index=0)") }
                            )
                            .concatWith(
                                Flux.just(createContentBlockStopEvent(0))
                                    .doOnNext { logger.debug("Sending event 4: content_block_stop (index=0)") }
                            )
                            .concatWith(
                                piiMaskingService.maskJson(bodyString)
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .doOnSubscribe {
                                        logger.debug("OLLAMA processing started in background")
                                        maskingSpan.event("ollama_processing_started")
                                    }
                                    .flatMapMany { maskedBody ->
                                        val maskingDuration = System.currentTimeMillis() - maskingStartTime
                                        val piiMaskingApplied = (maskedBody != bodyString)

                                        // Masking span에 결과 기록
                                        maskingSpan.tag("masking.duration_ms", maskingDuration.toString())
                                        maskingSpan.tag("masking.applied", piiMaskingApplied.toString())
                                        maskingSpan.tag("masking.masked_body_size", maskedBody.length.toString())
                                        maskingSpan.event("ollama_processing_completed")
                                        maskingSpan.end()

                                        logger.info("OLLAMA processing completed")
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

                                        // API Request Span 생성
                                        val apiSpan = tracer.nextSpan(parentSpan)
                                            .name("anthropic_api_request")
                                            .tag("api.url", targetBaseUrl + path)
                                            .tag("api.method", method.name())

                                        apiSpan.start()

                                        logger.debug("Sending event 5: content_block_start (index=1)")
                                        val completeText = "Masking completed (${maskingDuration}ms)\n\n"
                                        logger.debug("Sending event 6: content_block_delta (index=1)")

                                        val queryString = if (queryParams.isNotEmpty()) {
                                            queryParams.toSingleValueMap().map { "${it.key}=${it.value}" }.joinToString("&")
                                        } else {
                                            ""
                                        }
                                        val targetUrl = targetBaseUrl + path + if (queryString.isNotEmpty()) "?$queryString" else ""

                                        logger.info("Forwarding to API: {}", targetUrl)

                                        val webClient = webClientBuilder.build()

                                        logger.debug("Sending event 7: content_block_stop (index=1)")
                                        logger.debug("Starting API response streaming...")

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
                                                logger.debug("API response subscription started")
                                                apiSpan.event("api_response_started")
                                            }
                                            .doOnNext { buffer ->
                                                logger.trace("API chunk: {} bytes", buffer.readableByteCount())
                                            }
                                            .doOnComplete {
                                                logger.info("API response streaming completed")
                                                apiSpan.tag("api.response_bytes", totalResponseBytes.toString())
                                                apiSpan.event("api_response_completed")
                                                apiSpan.end()

                                                if (piiMaskingApplied) {
                                                    logger.info("PII MASKING APPLIED: Personal information was masked")
                                                } else {
                                                    logger.info("PII MASKING NOT APPLIED: No sensitive data found")
                                                }
                                            }
                                            .doOnError { error ->
                                                logger.error("API response error: {}", error.message)
                                                apiSpan.tag("api.error", error.message ?: "unknown")
                                                apiSpan.error(error)
                                                apiSpan.end()
                                            }

                                        Flux.just(createContentBlockStartEvent(1))
                                            .concatWith(Flux.just(createContentBlockDeltaEvent(1, completeText)))
                                            .concatWith(Flux.just(createContentBlockStopEvent(1)))
                                            .concatWith(apiResponseFlux)
                                    }
                                    .onErrorResume { error ->
                                        logger.error("OLLAMA processing failed: {}", error.message)
                                        maskingSpan.tag("masking.error", error.message ?: "unknown")
                                        maskingSpan.error(error)
                                        maskingSpan.end()

                                        // 실패 시 원본 데이터로 API 요청
                                        val fallbackSpan = tracer.nextSpan(parentSpan)
                                            .name("anthropic_api_request_fallback")
                                            .tag("api.fallback", "true")

                                        fallbackSpan.start()

                                        val targetUrl = targetBaseUrl + path
                                        logger.info("Forwarding original to API: {}", targetUrl)

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
                                                logger.info("Using original data (OLLAMA failed)")
                                            }
                                            .doOnComplete {
                                                fallbackSpan.end()
                                            }
                                            .doOnError { e ->
                                                fallbackSpan.error(e)
                                                fallbackSpan.end()
                                            }
                                    }
                            )
                            .concatWith(
                                Flux.just(createMessageDeltaEvent("end_turn"))
                                    .doOnNext { logger.debug("Sending event 8: message_delta") }
                            )
                            .concatWith(
                                Flux.just(createMessageStopEvent())
                                    .doOnNext { logger.debug("Sending event 9: message_stop") }
                            )
                            .doOnComplete {
                                val totalDuration = System.currentTimeMillis() - startTime
                                logger.info("=".repeat(80))
                                logger.info("STREAMING COMPLETED")
                                logger.info("Total duration: {}ms", totalDuration)
                                logger.info("=".repeat(80))
                                logger.info("")

                                // Parent span 완료
                                parentSpan.tag("http.status_code", "200")
                                parentSpan.tag("proxy.duration_ms", totalDuration.toString())
                                parentSpan.tag("response.total_bytes", totalResponseBytes.toString())
                                parentSpan.event("streaming_completed")
                                parentSpan.end()
                            }
                            .doOnError { error ->
                                parentSpan.tag("http.status_code", "500")
                                parentSpan.tag("error", error.message ?: "unknown")
                                parentSpan.error(error)
                                parentSpan.end()
                            }
                    }
                    .doOnSubscribe {
                        logger.info("SSE stream subscribed by client")
                        parentSpan.event("sse_stream_subscribed")
                    }

                } else {
                    // 마스킹 없이 바로 API 요청 (순수 프록시)
                    val apiSpan = tracer.nextSpan(parentSpan)
                        .name("anthropic_api_request_direct")
                        .tag("api.direct", "true")
                        .tag("pii.masking.skipped_reason",
                            if (!piiMaskingEnabled) "disabled"
                            else "body_too_large")

                    apiSpan.start()

                    if (piiMaskingEnabled && bodySize > piiMaskingMaxSize) {
                        logger.info("PII Masking ENABLED but size too large ({} bytes > {} bytes) - Skipping OLLAMA, using original", bodySize, piiMaskingMaxSize)
                    } else {
                        logger.info("PII Masking DISABLED - Pure proxy mode")
                    }

                    val queryString = if (queryParams.isNotEmpty()) {
                        queryParams.toSingleValueMap().map { "${it.key}=${it.value}" }.joinToString("&")
                    } else {
                        ""
                    }
                    val targetUrl = targetBaseUrl + path + if (queryString.isNotEmpty()) "?$queryString" else ""

                    logger.info("Forwarding to: $targetUrl")

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
                        .doOnSubscribe {
                            logger.info("Streaming response from API...")
                            apiSpan.event("api_streaming_started")
                        }
                        .doOnNext { buffer ->
                            totalResponseBytes += buffer.readableByteCount()
                            logger.debug("Forwarding API response buffer ({} bytes)", buffer.readableByteCount())
                        }
                        .doOnComplete {
                            val endTime = System.currentTimeMillis()
                            val duration = endTime - startTime
                            logger.info("Response streaming completed (Duration: {}ms)", duration)

                            apiSpan.tag("api.response_bytes", totalResponseBytes.toString())
                            apiSpan.event("api_streaming_completed")
                            apiSpan.end()

                            parentSpan.tag("http.status_code", "200")
                            parentSpan.tag("proxy.duration_ms", duration.toString())
                            parentSpan.tag("response.total_bytes", totalResponseBytes.toString())
                            parentSpan.event("proxy_completed")
                            parentSpan.end()

                            if (piiMaskingEnabled && bodySize > piiMaskingMaxSize) {
                                logger.info("PII MASKING SKIPPED: Request too large ({} bytes), sent original data", bodySize)
                            } else if (!piiMaskingEnabled) {
                                logger.info("PII MASKING DISABLED: Pure proxy mode, original data sent to API")
                            }
                            logger.info("=".repeat(80))
                            logger.info("")
                        }
                        .doOnError { error ->
                            apiSpan.tag("api.error", error.message ?: "unknown")
                            apiSpan.error(error)
                            apiSpan.end()

                            parentSpan.tag("http.status_code", "500")
                            parentSpan.error(error)
                            parentSpan.end()
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

                response.writeWith(errorHandledFlux)
            }
    }

    fun sendTestEvents(exchange: ServerWebExchange): Mono<Void> {
        val response = exchange.response
        val bufferFactory = response.bufferFactory()

        // Test span 생성
        val testSpan = tracer.nextSpan()
            .name("test_sse_events")
            .tag("test.type", "sse_event_test")

        testSpan.start()

        return exchange.request.body
            .next()
            .map { dataBuffer ->
                val bytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bytes)
                val requestBody = String(bytes, StandardCharsets.UTF_8)
                logger.info("Test endpoint - Request body: {}", requestBody.take(200))
                testSpan.tag("test.request_size", bytes.size.toString())
                requestBody
            }
            .flatMap { requestBody ->
                val messageId = "msg_test_${System.currentTimeMillis()}"

                val model = try {
                    val json = objectMapper.readTree(requestBody)
                    json.path("model").asText("claude-sonnet-4-5-20250929")
                } catch (e: Exception) {
                    "claude-sonnet-4-5-20250929"
                }

                fun createSSEEvent(event: String, data: String): DataBuffer {
                    val sseFormat = "event: $event\ndata: $data\n\n"
                    return bufferFactory.wrap(sseFormat.toByteArray(StandardCharsets.UTF_8))
                }

                fun createMessageStartEvent(): DataBuffer {
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

                val testResponse = """
                    Test response!

                    This message is sent through our own SSE events.
                    Generated directly without going through Anthropic API.

                    Check items:
                    1. message_start event sent?
                    2. content_block_start event sent?
                    3. content_block_delta events sent?
                    4. content_block_stop event sent?
                    5. message_delta event sent?
                    6. message_stop event sent?

                    If all events are sent in correct order, it will display properly in CLAUDE CODE.
                """.trimIndent()

                response.headers.set("Content-Type", "text/event-stream")
                response.headers.set("Cache-Control", "no-cache")
                response.headers.set("Connection", "keep-alive")

                val eventFlux = Mono.defer<Unit> {
                    logger.info("Test endpoint called - sending dummy SSE events")
                    testSpan.event("test_started")
                    Mono.just(Unit)
                }.flatMapMany {
                    Flux.just(createMessageStartEvent())
                        .doOnNext {
                            logger.debug("Test: message_start sent")
                            testSpan.event("message_start_sent")
                        }
                        .concatWith(Flux.just(createContentBlockStartEvent(0))
                            .doOnNext {
                                logger.debug("Test: content_block_start sent")
                                testSpan.event("content_block_start_sent")
                            })
                        .concatWith(Flux.fromArray(testResponse.chunked(50).map { chunk ->
                            createContentBlockDeltaEvent(0, chunk + "\n")
                        }.toTypedArray())
                            .doOnNext { logger.debug("Test: content_block_delta sent") })
                        .concatWith(Flux.just(createContentBlockStopEvent(0))
                            .doOnNext {
                                logger.debug("Test: content_block_stop sent")
                                testSpan.event("content_block_stop_sent")
                            })
                        .concatWith(Flux.just(createMessageDeltaEvent())
                            .doOnNext {
                                logger.debug("Test: message_delta sent")
                                testSpan.event("message_delta_sent")
                            })
                        .concatWith(Flux.just(createMessageStopEvent())
                            .doOnNext {
                                logger.debug("Test: message_stop sent")
                                testSpan.event("message_stop_sent")
                            })
                        .doOnComplete {
                            logger.info("Test: All events sent successfully")
                            testSpan.tag("test.status", "success")
                            testSpan.event("test_completed")
                            testSpan.end()
                        }
                        .doOnError { error ->
                            testSpan.tag("test.status", "failed")
                            testSpan.tag("test.error", error.message ?: "unknown")
                            testSpan.error(error)
                            testSpan.end()
                        }
                }

                response.writeWith(eventFlux)
            }
    }

}
