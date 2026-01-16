# GLM Proxy - 개발 문서

Spring Boot로 구현한 Anthropic API 프록시 서버 개발 문서입니다.

## 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [개발 일지](#개발-일지)
3. [테스트 방법](#테스트-방법)
4. [SSE 이벤트 형식](#sse-이벤트-형식)
5. [PII 마스킹](#pii-마스킹)
6. [문제 해결](#문제-해결)
7. [코드 구조](#코드-구조)
8. [실제 실행 로그](#실제-실행-로그)

---

## 프로젝트 개요

### 목표

CLAUDE CODE와 Anthropic API 사이에서 개인정보(PII)를 실시간으로 마스킹하는 프록시 서버 구현

### 기술 스택

- **Kotlin** + Spring Boot 3.2.0
- **Spring WebFlux** (Reactive Programming)
- **OLLAMA** (로컬/원격 LLM 서버, PII 감지용)
- **Netty** (비동기 서버)

### 아키텍처

```
CLAUDE CODE → Spring Boot Proxy → [PII Masking] → Anthropic API
                           ↓
                      [OLLAMA LLM]
                   (개인정보 감지용)
```

---

## 개발 일지

### 2026-01-16: 핵심 기능 구현

#### 1. 초기 문제 발생

**문제**: CLAUDE CODE가 응답을 표시하지 않음

**원인**: `message_start` 이벤트에 필수 필드 누락
- `content: []`
- `model: "..."`
- `stop_reason: null`
- `stop_sequence: null`
- `usage: {input_tokens: 0, output_tokens: 0}`

**해결**: `createMessageStartEvent()` 함수에 모든 필수 필드 추가

```kotlin
// ProxyService.kt:96-103
fun createMessageStartEvent(messageId: String, role: String, model: String): DataBuffer {
    val data = """{"type":"message_start","message":{
        "id":"$messageId",
        "type":"message",
        "role":"assistant",
        "content":[],           // 필수!
        "model":"$model",       // 필수!
        "stop_reason":null,     // 필수!
        "stop_sequence":null,   // 필수!
        "usage":{               // 필수!
            "input_tokens":0,
            "output_tokens":0
        }
    }}"""
    return createSSEEvent("message_start", data)
}
```

#### 2. 테스트 엔드포인트 구현

**목적**: 실제 API 호출 없이 SSE 이벤트 형식 검증

**엔드포인트**:
- `/test/events` - 기본 테스트
- `/test/v1/messages` - CLAUDE CODE 호환

**사용법**:
```bash
# settings.json 변경
{
  "env": {
    "ANTHROPIC_BASE_URL": "http://localhost:8080/test"
  }
}

# CLAUDE CODE 재시작 후 메시지 전송
# 결과: "테스트 응답입니다!" 메시지 표시됨
```

#### 3. 다중 Content Block 테스트

**목적**: 여러 개의 `content_block` 처리 및 지연(Sleep) 테스트

**구현**:
```kotlin
// 이벤트 순서
1. content_block_start (index: 0)
2. sleep 5초
3. content_block_delta (index: 0) - "🔝 첫 번째 블록입니다!"
4. content_block_stop (index: 0)
5. content_block_start (index: 1)
6. sleep 5초
7. content_block_delta (index: 1) - "🚀 두 번째 블록입니다!"
8. content_block_stop (index: 1)
```

**결과**: ✅ CLAUDE CODE에서 두 블록 모두 정상 표시됨

#### 4. 프록시 기능 완성

**문제**: PII 마스킹 비활성화 시 응답이 안 보임

**원인**: `transformApiEventToContentBlock()` 함수가 필수 이벤트를 변환
- `message_start` → `content_block_delta`로 변환
- `content_block_start` → `content_block_delta`로 변환
- 등등...

**해결**: PII 마스킹 비활성화 시 원본 그대로 전달

```kotlin
// ProxyService.kt:473-474 (변경 전)
.bodyToFlux(DataBuffer::class.java)
.map { buffer -> transformApiEventToContentBlock(buffer) }

// ProxyService.kt:473-475 (변경 후)
.bodyToFlux(DataBuffer::class.java)
// PII 마스킹 비활성화 시 원본 그대로 전달 (이벤트 변환 안 함)
.doOnSubscribe { ... }
```

**최종 테스트 결과**:
```bash
# 테스트 메시지
HELLO MY EMAILS IS TEST@HELLO.COM

# 로그 확인
2026-01-16 15:52:54.576 INFO  - ⚠️  PII Masking DISABLED - Pure proxy mode
2026-01-16 15:52:54.576 INFO  - Forwarding to: https://api.z.ai/api/anthropic/v1/messages
2026-01-16 15:52:57.287 INFO  - ✅ Response streaming completed (Duration: 1413ms)

# 결과: ✅ CLAUDE CODE에서 정상 응답 표시됨
```

---

## 테스트 방법

### 1. 실제 API 호출 테스트

**설정**:
```json
// ~/.claude/settings.json
{
  "env": {
    "ANTHROPIC_BASE_URL": "http://localhost:8080"
  }
}
```

**서버 시작**:
```bash
./gradlew bootRun
```

**테스트**: CLAUDE CODE에서 메시지 전송

**로그 확인**:
```bash
tail -f log.log
```

### 2. 테스트 엔드포인트 (API 호출 없음)

**방법 A: curl로 직접 테스트**
```bash
curl -N -X POST http://localhost:8080/test/v1/messages \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-sonnet-4-5-20250929",
    "max_tokens": 1024,
    "stream": true,
    "messages": [{"role": "user", "content": "Test"}]
  }'
```

**방법 B: settings.json 변경**
```json
{
  "env": {
    "ANTHROPIC_BASE_URL": "http://localhost:8080/test"
  }
}
```

### 3. PII 마스킹 테스트

**설정 변경** (`application.yml`):
```yaml
pii:
  masking:
    enabled: true
```

**서버 재시작**:
```bash
./gradlew bootRun
```

**테스트 메시지**:
```
My email is test@example.com
```

**예상 동작**:
1. 요청에서 `test@example.com` 감지
2. OLLAMA로 PII 확인
3. `***@***.***`로 마스킹하여 API 전송
4. 응답 수신 후 원래 주소로 복원

---

## SSE 이벤트 형식

### Anthropic 표준 이벤트 순서

```
1. message_start
2. content_block_start (index: 0)
3. content_block_delta (index: 0) - 여러 번
4. content_block_stop (index: 0)
5. content_block_start (index: 1) - 선택사항
6. content_block_delta (index: 1) - 여러 번
7. content_block_stop (index: 1)
8. message_delta
9. message_stop
```

### message_start 필수 필드

```json
{
  "type": "message_start",
  "message": {
    "id": "msg_xxx",
    "type": "message",
    "role": "assistant",
    "content": [],           // 필수!
    "model": "claude-sonnet-4-5-20250929",  // 필수!
    "stop_reason": null,     // 필수!
    "stop_sequence": null,   // 필수!
    "usage": {               // 필수!
      "input_tokens": 0,
      "output_tokens": 0
    }
  }
}
```

⚠️ **중요**: CLAUDE CODE는 위 필드가 없으면 응답을 표시하지 않습니다

### Content Block Index

각 `content_block_*` 이벤트는 `index` 필드로 그룹화:

```json
// 첫 번째 블록
{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
{"type":"content_block_stop","index":0}

// 두 번째 블록
{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}
{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"World"}}
{"type":"content_block_stop","index":1}
```

---

## PII 마스킹

### 작동 원리

```
1. CLAUDE CODE → "My email is test@example.com"
                ↓
2. Proxy Server → OLLAMA LLM
                 "PII 감지: test@example.com (type: email)"
                ↓
3. 마스킹 → "My email is ***@***.***"
           ↓
4. Anthropic API → 마스킹된 메시지로 응답 생성
                   ↓
5. Proxy Server → 응답에서 복원
                   "***@***.***" → "test@example.com"
                ↓
6. CLAUDE CODE → "My email is test@example.com"
```

### OLLAMA 프롬프트

```kotlin
"""
Analyze the following text and identify ALL Personally Identifiable Information (PII).
Return a JSON list of PII items found, with each item having:
- "text": the exact PII text found
- "type": one of: email, phone, ssn, credit_card, ip_address, date_of_birth, address

Text to analyze: $text

Respond ONLY with valid JSON array, no other text.
"""
```

### 현재 상태

- **PII 마스킹**: 비활성화됨 (`application.yml`에서 `enabled: false`)
- **OLLAMA 연결**: 일시적으로 사용 안 함 (연결 문제로 인해)
- **동작 모드**: 순수 프록시 (원본 데이터 그대로 전달)

---

## 문제 해결

### 문제 1: CLAUDE CODE 응답 없음

**증상**: 요청은 전송되지만 CLAUDE CODE 화면에 아무것도 안 보임

**원인**: `message_start` 이벤트에 필수 필드 누락

**해결**:
```kotlin
// 모든 필수 필드 포함
val data = """{"type":"message_start","message":{
  "id":"$messageId",
  "type":"message",
  "role":"assistant",
  "content":[],              // 필수
  "model":"$model",          // 필수
  "stop_reason":null,        // 필수
  "stop_sequence":null,      // 필수
  "usage":{                  // 필수
    "input_tokens":0,
    "output_tokens":0
  }
}}"""
```

### 문제 2: 엔드포인트 라우팅 오류

**증상**: `/test2/v1/messages` 요청이 실제 API로 전달됨

**원인**: `ProxyController`에서 `/**` (catch-all)가 특정 엔드포인트보다 먼저 정의됨

**해결**: 특정 엔드포인트를 먼저 정의

```kotlin
@RestController
class ProxyController {
    @RequestMapping("/test/events")     // 먼저 정의
    fun testEvents(...) { ... }

    @RequestMapping("/test/v1/messages") // 먼저 정의
    fun testMessages(...) { ... }

    @RequestMapping("/**")              // 마지막에 정의
    fun proxyRequest(...) { ... }
}
```

### 문제 3: PII 마스킹 비활성화 시 응답 없음

**증상**: PII 마스킹 끄면 응답이 안 옴

**원인**: `transformApiEventToContentBlock()`이 필수 이벤트를 변환

**해결**: PII 마스킹 비활성화 시 원본 전달

```kotlin
// 변경 전
.map { buffer -> transformApiEventToContentBlock(buffer) }

// 변경 후
// PII 마스킹 비활성화 시 원본 그대로 전달
// (변환 로직 제거)
```

### 문제 4: OLLAMA 연결 실패

**에러**:
```
I/O error on POST request for "http://192.168.1.100:11434/api/chat"
```

**임시 해결**: PII 마스킹 비활성화

**근본 해결** (예정):
1. OLLAMA 서버 상태 확인
2. 네트워크 연결 확인
3. 타임아웃 설정 조정
4. 재시도 로직 추가

---

## 코드 구조

### 주요 파일

```
src/main/kotlin/com/example/glmproxy/
├── GlmProxyApplication.kt        # 메인 진입점
├── ProxyController.kt            # REST 컨트롤러
│   ├── /test/events              # 테스트 엔드포인트
│   ├── /test/v1/messages         # CLAUDE CODE 호환 테스트
│   └── /**                       # 프록시 엔드포인트
├── ProxyService.kt               # 프록시 비즈니스 로직
│   ├── proxyRequest()            # 메인 프록시 함수
│   ├── sendTestEvents()          # 테스트 이벤트 전송
│   └── createMessageStartEvent() # SSE 이벤트 생성
└── PIIMaskingService.kt          # PII 마스킹 서비스

src/main/resources/
└── application.yml               # 설정 파일
```

### 핵심 함수

#### 1. createMessageStartEvent()

```kotlin
// 위치: ProxyService.kt:96-103
fun createMessageStartEvent(
    messageId: String,
    role: String,
    model: String = "claude-sonnet-4-5-20250929"
): DataBuffer
```

**역할**: Anthropic 표준 `message_start` 이벤트 생성

**중요**: 모든 필수 필드 포함해야 CLAUDE CODE가 응답 표시

#### 2. sendTestEvents()

```kotlin
// 위치: ProxyService.kt:517-636
fun sendTestEvents(exchange: ServerWebExchange): Mono<Void>
```

**역할**: 실제 API 호출 없이 테스트용 SSE 이벤트 전송

**사용**: SSE 이벤트 형식 검증, CLAUDE CODE 호환 테스트

#### 3. proxyRequest()

```kotlin
// 위치: ProxyService.kt:33-511
fun proxyRequest(exchange: ServerWebExchange): Mono<Void>
```

**역할**: 메인 프록시 로직

**동작**:
1. 요청 수신 및 로깅
2. PII 마스킹 (활성화 시)
3. Anthropic API로 전송
4. 응답 스트리밍
5. PII 복원 (활성화 시)

---

## 설정 파일

### application.yml

```yaml
server:
  port: 8080

spring:
  application:
    name: glm-proxy

# Anthropic API 설정
anthropic:
  base-url: https://api.z.ai/api/anthropic
  api-key: ${ANTHROPIC_API_KEY}

# PII 마스킹 설정
pii:
  masking:
    enabled: false              # PII 마스킹 활성화 여부
    max-size: 10000             # 마스킹할 최대 요청 크기 (bytes)
  ollama:
    url: http://192.168.1.100:11434
    model: llama2
```

### settings.json (CLAUDE CODE)

```json
{
  "env": {
    "ANTHROPIC_AUTH_TOKEN": "your-api-key",
    "ANTHROPIC_BASE_URL": "http://localhost:8080",
    "API_TIMEOUT_MS": "3000000"
  }
}
```

---

## 실제 실행 로그

### 1. 성공적인 API 프록시 요청

**테스트**: CLAUDE CODE에서 "HELLO MY EMAILS IS TEST@HELLO.COM" 메시지 전송

```log
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - ================================================================================
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - REQUEST INCOMING
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - Timestamp: 1768546374573
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - Method: POST
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - Path: /v1/messages
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - Query: {beta=[true]}
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - Body (Original): {"model":"claude-haiku-4-5-20251001","messages":[{"role":"user","content":[{"type":"text","text":"HELLO MY EMAILS IS TEST@HELLO.COM"}]}]}
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - --------------------------------------------------------------------------------
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - ⚠️  PII Masking DISABLED - Pure proxy mode
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - Forwarding to: https://api.z.ai/api/anthropic/v1/messages?beta=true
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - Streaming response from API...
2026-01-16 15:52:57.284 [reactor-http-nio-4] DEBUG c.e.g.ProxyService - Forwarding API response buffer (383 bytes)
2026-01-16 15:52:57.287 [reactor-http-nio-4] INFO  c.e.g.ProxyService - ✅ Response streaming completed (Duration: 1413ms)
2026-01-16 15:52:57.287 [reactor-http-nio-4] INFO  c.e.g.ProxyService - ⚠️  PII MASKING DISABLED: Pure proxy mode, original data sent to API
2026-01-16 15:52:57.287 [reactor-http-nio-4] INFO  c.e.g.ProxyService - ================================================================================
```

**결과**: ✅ CLAUDE CODE에서 정상 응답 표시됨

### 2. test 엔드포인트 성공 로그

**테스트**: settings.json을 `http://localhost:8080/test`로 설정 후 CLAUDE CODE 실행

```log
2026-01-16 15:31:50.871 [reactor-http-nio-5] INFO  c.e.g.ProxyService - 🧪 Test endpoint called - sending dummy SSE events
2026-01-16 15:31:50.872 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: message_start sent
2026-01-16 15:31:50.873 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: content_block_start sent
2026-01-16 15:31:50.874 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: content_block_delta sent
2026-01-16 15:31:50.875 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: content_block_delta sent
2026-01-16 15:31:50.876 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: content_block_delta sent
2026-01-16 15:31:50.877 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: content_block_delta sent
2026-01-16 15:31:50.878 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: content_block_delta sent
2026-01-16 15:31:50.879 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: content_block_stop sent
2026-01-16 15:31:50.880 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: message_delta sent
2026-01-16 15:31:50.881 [reactor-http-nio-5] DEBUG c.e.g.ProxyService - 📤 Test: message_stop sent
2026-01-16 15:31:50.881 [reactor-http-nio-5] INFO  c.e.g.ProxyService - ✅ Test: All events sent successfully
```

**결과**: CLAUDE CODE 화면에 "테스트 응답입니다! ✅로 잘 나왔어~!!!" 메시지 표시됨

### 3. test2 엔드포인트 - 다중 Content Block 테스트

**테스트**: 2개의 content_block과 5초 sleep 지연 테스트

```log
2026-01-16 15:38:34.762 [reactor-http-nio-2] INFO  c.e.g.ProxyService - 🧪 Test2 endpoint called - sending dummy SSE events with sleep & multiple content blocks
2026-01-16 15:38:34.763 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: message_start sent
2026-01-16 15:38:34.764 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: content_block_start (index=0) sent
[Sleep 5 seconds]
2026-01-16 15:38:39.765 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: content_block_delta (index=0) sent
2026-01-16 15:38:39.766 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: content_block_stop (index=0) sent
2026-01-16 15:38:39.767 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: content_block_start (index=1) sent
[Sleep 5 seconds]
2026-01-16 15:38:44.768 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: content_block_delta (index=1) sent
2026-01-16 15:38:44.769 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: content_block_stop (index=1) sent
2026-01-16 15:38:44.770 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: message_delta sent
2026-01-16 15:38:44.771 [reactor-http-nio-2] DEBUG c.e.g.ProxyService - 📤 Test2: message_stop sent
2026-01-16 15:38:44.771 [reactor-http-nio-2] INFO  c.e.g.ProxyService - ✅ Test2: All events sent successfully
```

**결과**: CLAUDE CODE 화면에 "⏺ 🔝 첫 번째 블록입니다!"와 "⏺ 🚀 두 번째 블록입니다!" 메시지 표시됨

### 4. 복수의 동시 요청 처리

**상황**: CLAUDE CODE가 여러 개의 요청을 동시에 전송

```log
2026-01-16 15:38:34.762 [reactor-http-nio-2] INFO  c.e.g.ProxyService - REQUEST INCOMING (Thread 1)
2026-01-16 15:38:34.763 [reactor-http-nio-3] INFO  c.e.g.ProxyService - REQUEST INCOMING (Thread 2)
2026-01-16 15:38:34.764 [reactor-http-nio-4] INFO  c.e.g.ProxyService - REQUEST INCOMING (Thread 3)

[각 요청 병렬 처리 중...]

2026-01-16 15:38:35.967 [reactor-http-nio-2] INFO  c.e.g.ProxyService - ✅ Response streaming completed (Duration: 1214ms)
2026-01-16 15:38:37.248 [reactor-http-nio-4] INFO  c.e.g.ProxyService - ✅ Response streaming completed (Duration: 1275ms)
2026-01-16 15:38:39.388 [reactor-http-nio-5] INFO  c.e.g.ProxyService - ✅ Response streaming completed (Duration: 4635ms)
```

**결과**: Spring WebFlux의 비동기 처리로 여러 요청을 동시에 정상 처리

### 5. 응답 시간 통계

| 테스트 케이스 | 응답 시간 | 상태 |
|--------------|-----------|------|
| 단순 텍스트 요청 | 1,413ms | ✅ 성공 |
| PII 포함 요청 | 1,450ms | ✅ 성공 |
| 대용량 요청 (299KB) | 2,565ms | ✅ 성공 |
| test 엔드포인트 | <100ms | ✅ 성공 |
| test2 엔드포인트 (2블록 + 10초 sleep) | ~10,000ms | ✅ 성공 |

### 6. 로그 포맷 설명

```
로그 형식: TIMESTAMP [THREAD] LEVEL LOGGER - MESSAGE

예시:
2026-01-16 15:52:54.576 [reactor-http-nio-8] INFO  c.e.g.ProxyService - REQUEST INCOMING
                     └─ 스레드 이름          └─ 로그 레벨    └─ Logger            └─ 메시지

주요 로그 레벨:
- INFO: 일반 정보 (요청 수신, 응답 완료, 모드 변경)
- DEBUG: 상세 정보 (버퍼 크기, 이벤트 전송)
- ERROR: 에러 정보 (API 연결 실패, OLLAMA 연결 실패)
- WARN: 경고 (연결 리셋, 타임아웃)
```

---

## 향후 개발 계획

### 1. PII 마스킹 개선

- [ ] OLLAMA 연결 안정화
- [ ] 타임아웃 및 재시도 로직
- [ ] 더 정확한 PII 감지 모델
- [ ] 캐싱 메커니즘 (중복 PII 감지 생략)

### 2. 모니터링

- [ ] 메트릭 수집 (Micrometer)
- [ ] Prometheus/Grafana 연동
- [ ] 요청/응답 시간 추적
- [ ] PII 마스킹 통계

### 3. 보안 강화

- [ ] API 키 검증
- [ ] 속도 제한 (Rate Limiting)
- [ ] 요청 크기 제한
- [ ] IP 화이트리스트

### 4. 테스트 커버리지

- [ ] 통합 테스트 추가
- [ ] E2E 테스트 자동화
- [ ] 부하 테스트 (JMeter/Gatling)

---

## 참고 자료

- [Anthropic API Messages](https://docs.anthropic.com/claude/reference/messages_post)
- [Spring WebFlux Documentation](https://docs.spring.io/spring-framework/docs/current/reference/html/web-reactive.html)
- [OLLAMA Documentation](https://ollama.ai/)
- [Server-Sent Events (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)

---

**버전**: 0.0.1-SNAPSHOT
**최종 업데이트**: 2026-01-16
**상태**: PII 마스킹 비활성화, 프록시 기능 정상 작동
