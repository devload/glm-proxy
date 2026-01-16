# PII 마스킹 상태 알림 구현 방안

## 문제점

현재 OLLAMA가 PII 마스킹하는 동안(약 5-10초) 클라이언트는 아무런 응답 없이 기다려야 합니다.

```
Client                  Proxy                    OLLAMA
  |                        |                        |
  |--POST /messages------->|                        |
  |                        |                        |
  |                        |--mask JSON------------>|
  |                        |      (5-10초 대기)       |
  |                        |<--masked JSON----------|
  |                        |                        |
  |                        |--API request---------->|
  |<--Response------------|                        |

클라이언트는 (1)-(5) 전체 과정 동안 응답 없음
```

## 해결 방안

### 옵션 1: HTTP 응답 헤더 (현재 구현됨) ✅

**장점:**
- 구현 간단
- 기존 코드 유지
- 모든 HTTP 클라이언트가 지원

**단점:**
- 응답을 받기 전까지 상태를 알 수 없음
- 사용자에게 실시간으로 보이지 않음

```kotlin
// 응답 헤더에 추가
response.headers["X-PII-Masked"] = "true"
response.headers["X-PII-Masking-Method"] = "OLLAMA (qwen2.5)"
response.headers["X-PII-Masking-Duration"] = "5234ms"
```

**확인 방법:**
```bash
curl -I http://localhost:8080/v1/messages
# X-PII-Masked: true
# X-PII-Masking-Duration: 5234ms
```

---

### 옵션 2: SSE 스트리밍으로 상태 전달 (권장) ⭐

**개념:** 마스킹 상태를 SSE 이벤트로 먼저 보내고, 그 다음 API 응답을 스트리밍

```
Client                  Proxy
  |                        |
  |--POST /messages------->|
  |                        |
  |<--event: masking_start--|  ← 즉시 응답
  |<--data: {"status":"Masking PII with OLLAMA..."}|
  |                        |
  |                        |[OLLAMA 처리 중...]
  |                        |
  |<--event: masking_complete|
  |<--data: {"duration":"5.2s","applied":true}|
  |                        |
  |<--event: message_start|  ← API 응답 시작
  |<--event: content_block_delta
  |<--data: {"text":"안녕"}
  |                        |
  |<--event: message_stop|
```

**구현 예시:**

```kotlin
fun proxyRequest(exchange: ServerWebExchange): Mono<Void> {
    val response = exchange.response
    response.headers.contentType = MediaType.parseMediaType("text/event-stream")

    val outputStream = response.bufferFactory().outputStream()

    // 마스킹 시작 이벤트 전송
    outputStream.write("""
        event: masking_start
        data: {"status":"PII Masking started with OLLAMA (qwen2.5)"}

    """.trimIndent().toByteArray())

    // 마스킹 처리
    return piiMaskingService.maskJson(bodyString)
        .flatMap { maskedBody ->
            // 마스킹 완료 이벤트 전송
            outputStream.write("""
                event: masking_complete
                data: {"duration":"${duration}ms","applied":${maskedBody != bodyString}}

            """.trimIndent().toByteArray())

            // API 요청 및 응답 스트리밍
            forwardToApi(maskedBody)
        }
}
```

**장점:**
- 클라이언트가 실시간으로 상태 확인
- 사용자에게 "마스킹 중" 메시지 표시 가능
- Anthropic API와 동일한 SSE 방식

**단점:**
- 구현 복잡
- 현재 코드를 크게 변경해야 함
- 클라이언트가 SSE를 파싱해야 함

---

### 옵션 3: HTTP 102 Processing (비권장)

HTTP 102 상태 코드로 "처리 중"임을 알림:

```kotlin
// 즉시 102 응답 전송
response.statusCode = HttpStatus.PROCESSING // 102
response.writeWith(Mono.just(buffer))
    .then(
        // 실제 처리 후 다시 응답
        processRequest().flatMap { realResponse ->
            response.statusCode = realResponse.statusCode
            response.writeWith(realResponse.body)
        }
    )
```

**문제점:**
- 많은 HTTP 클라이언트가 102를 제대로 처리하지 않음
- 두 번의 응답을 보내야 해서 복잡함

---

### 옵션 4: 비동기 상태 조회 엔드포인트 (대안)

별도 API로 마스킹 상태 조회:

```kotlin
// 상태 저장
val maskingStatus = ConcurrentHashMap<String, MaskingState>()

// 상태 조회 엔드포인트
@GetMapping("/api/masking/status/{requestId}")
fun getMaskingStatus(@PathVariable requestId: String): MaskingState {
    return maskingStatus[requestId]
}
```

```javascript
// 클라이언트에서 폴링
const requestId = generateId()
post('/v1/messages', { id: requestId, ... })

// 주기적으로 상태 확인
const interval = setInterval(() => {
  fetch(`/api/masking/status/${requestId}`)
    .then(res => res.json())
    .then(status => {
      if (status.state === 'completed') {
        clearInterval(interval)
      }
    })
}, 500)
```

---

## 추천 사항

### 단기 (현재): HTTP 헤더 ✅
- 이미 구현됨
- 로그와 헤더로 상태 확인 가능

### 중기: SSE 스트리밍 구현
- 사용자 경험 개선
- "마스킹 중..." 표시 가능
- Anthropic API와 동일한 패턴

### 장기: 전용 상태 API
- 웹 대시보드에서 모니터링
- 복잡한 통계 및 분석

---

## 현재 사용 가능한 방법

### 1. 터미널 로그 확인
```bash
./gradlew bootRun

# 로그 출력:
# 🔒 PII Masking ENABLED (size: 868 bytes <= 5000 bytes)
# 🔒 PII Masking COMPLETED (applied: true)
```

### 2. HTTP 헤더 확인
```bash
curl -v http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-3","messages":[{"role":"user","content":"My email is test@example.com"}]}'

# 출력:
# < HTTP/1.1 200 OK
# < X-PII-Masked: true
# < X-PII-Masking-Method: OLLAMA (qwen2.5)
```

### 3. curl로 타임스탬프 비교
```bash
# 요청 시작 시간 기록
START=$(date +%s%3N)

curl http://localhost:8080/v1/messages ...

# 응답 받은 후
END=$(date +%s%3N)
DURATION=$((END - START))

echo "Total time: ${DURATION}ms"
# OLLAMA 마스킹 시간: 약 5-10초
```

---

## 결론

**현재技术上**로는 OLLAMA 처리 중 실시간 상태를 전달할 수 있지만, 클라이언트(CLAUDE CODE)가 이를 파싱해서 표시하도록 수정해야 합니다.

가장 현실적인 방법은 **SSE 스트리밍** 구현이지만, 이는:
1. 프록시 서버 코드를 크게 변경해야 함
2. CLAUDE CODE 클라이언트도 SSE를 파싱하도록 수정해야 함
3. 현재로서는 **서버 로그**와 **HTTP 헤더**로 확인하는 것이 현실적임
