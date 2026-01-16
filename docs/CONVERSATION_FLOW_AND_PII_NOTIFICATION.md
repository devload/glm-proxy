# 대화 흐름과 PII 마스킹 알림

## 1. HTTP 대화가 진행되는 방식 (SSE 스트리밍)

### Server-Sent Events (SSE) 구조

Anthropic API는 하나의 HTTP 요청 안에서 **여러 이벤트를 스트리밍**합니다:

```
POST /v1/messages
Content-Type: application/json

{
  "messages": [
    {"role": "user", "content": "안녕하세요"}
  ]
}
```

**응답 (text/event-stream):**

```
event: message_start
data: {"type": "message_start", "message": {"id": "msg_123", ...}}

event: content_block_start
data: {"type": "content_block_start", "index": 0, ...}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "안녕"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "하세요!"}}

event: content_block_stop
data: {"type": "content_block_stop", "index": 0}

event: message_stop
data: {"type": "message_stop", "stop_reason": "end_turn"}
```

### 대화 흐름

```
┌─────────┐      POST /v1/messages       ┌──────────┐
│ Client  │ ──────────────────────────>  │   Proxy  │
└─────────┘                               └──────────┘
     │                                          │
     │         Forward (PII masked)             │
     │ <─────────────────────────────────────>  │
     │                                          │
     │         SSE Stream (chunks)              │
     │ <─────────────────────────────────────   │
     │     event: content_block_delta           │
     │     data: {"text": "안녕"}               │
     │                                          │
     │     event: content_block_delta           │
     │     data: {"text": "하세요!"}            │
     │                                          │
     │     event: message_stop                  │
     │ <─────────────────────────────────────   │
```

## 2. 대화가 종료되는 시점

`message_stop` 이벤트가 도착하면 대화가 종료됩니다:

```json
event: message_stop
data: {
  "type": "message_stop",
  "stop_reason": "end_turn"  // 또는 "max_tokens", "stop_sequence"
}
```

## 3. "Roosting…" 메시지의 정체

이것은 **Claude Code CLI의 UI**로, 프록시 서버에서 제어할 수 없습니다:

```
✻ Roosting… (esc to interrupt · 2m 4s · ↑ 1.3k tokens · thinking)
```

- **esc to interrupt**: 사용자가 ESC 키를 누르면 응답 중단
- **2m 4s**: 응답 생성 시간
- **↑ 1.3k tokens**: 사용된 토큰 수
- **thinking**: 현재 모델이 생각 중 (thinking 모드)

이 메시지는 클라이언트(CLAUDE CODE)가 스트리밍 응답을 받으면서 실시간으로 표시합니다.

## 4. PII 마스킹 알림 표시

### 현재 구현된 방법

#### 4.1 서버 로그 (터미널에서 확인)

```log
2026-01-16 11:00:00.000 [reactor-http-nio-1] INFO  ProxyService - ================================================================================
2026-01-16 11:00:00.100 [reactor-http-nio-1] INFO  ProxyService - REQUEST INCOMING
2026-01-16 11:00:00.200 [reactor-http-nio-1] INFO  ProxyService - 🔒 PII Masking ENABLED (size: 3000 bytes <= 5000 bytes) - Processing with OLLAMA...
2026-01-16 11:00:05.500 [reactor-http-nio-1] INFO  ProxyService - 🔒 PII Masking COMPLETED (applied: true)
2026-01-16 11:00:10.000 [reactor-http-nio-1] INFO  ProxyService - RESPONSE FROM TARGET
2026-01-16 11:00:10.100 [reactor-http-nio-1] INFO  ProxyService - 🔒 PII MASKING APPLIED: Personal information was masked before sending to API
2026-01-16 11:00:10.200 [reactor-http-nio-1] INFO  ProxyService - Status: 200 OK
2026-01-16 11:00:10.300 [reactor-http-nio-1] INFO  ProxyService - Duration: 10200ms
2026-01-16 11:00:10.400 [reactor-http-nio-1] INFO  ProxyService - ================================================================================
```

#### 4.2 HTTP 응답 헤더

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-PII-Masked: true
X-PII-Masking-Method: OLLAMA (qwen2.5)

{
  "id": "msg_123",
  "type": "message",
  ...
}
```

클라이언트가 이 헤더를 확인할 수 있습니다:

```javascript
// 예: JavaScript로 헤더 확인
fetch('http://localhost:8080/v1/messages', {
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({...})
})
.then(response => {
  console.log('PII Masked:', response.headers.get('X-PII-Masked'));
  console.log('Method:', response.headers.get('X-PII-Masking-Method'));
});
```

### 로그 메시지 종류

| 상태 | 로그 메시지 | 설명 |
|------|-----------|------|
| 마스킹 적용 | 🔒 PII MASKING APPLIED | OLLAMA가 개인정보를 마스킹함 |
| 마스킹 스킵 (대용량) | ⚠️ PII MASKING SKIPPED | 요청이 너무 커서 마스킹하지 않음 |
| 마스킹 비활성화 | ⚠️ PII MASKING DISABLED | 마스킹 기능이 꺼져 있음 |

### 사용자에게 알림 표시 (미래 구현 아이디어)

#### 아이디어 1: 시스템 프롬프트 주입

```kotlin
// 요청에 알림 추가
val systemMessage = """
  [SYSTEM NOTIFICATION: 개인정보가 마스킹되어 API에 전송되었습니다]

  Original: user_id=user_12345
  Masked:   user_id=[USER_ID]
"""

val modifiedRequest = maskedBodyString + systemMessage
```

#### 아이디어 2: 응답 프리앰블 추가

```kotlin
// 응답 앞에 알림 추가 (텍스트 응답일 때만)
val notification = "[🔒 개인정보가 마스킹되었습니다]\n\n"
val modifiedResponse = notification + responseBody
```

**주의:** JSON 응답 구조를 변경하면 파싱 오류가 발생할 수 있으므로 주의가 필요합니다.

## 5. 요약

| 구성요소 | 설명 |
|---------|------|
| **대화 진행 방식** | HTTP SSE 스트리밍 (여러 이벤트를 하나의 연결에서 전송) |
| **종료 시점** | `message_stop` 이벤트 수신 |
| **Roosting 메시지** | 클라이언트 UI, 프록시에서 제어 불가 |
| **PII 마스킹 알림** | 서버 로그 + HTTP 응답 헤더 (`X-PII-Masked`) |

## 6. 현재 사용자가 알림을 확인하는 방법

### 방법 1: 터미널에서 프록시 로그 확인

```bash
# 프록시 서버 실행
./gradlew bootRun

# 로그 출력 확인
# 🔒 PII MASKING APPLIED: Personal information was masked before sending to API
```

### 방법 2: HTTP 헤더 확인 (개발자 도구)

```bash
# curl로 헤더 확인
curl -v http://localhost:8080/v1/messages \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-3","messages":[{"role":"user","content":"Hello"}]}'

# 출력:
# < X-PII-Masked: true
# < X-PII-Masking-Method: OLLAMA (qwen2.5)
```

### 방법 3: 로그 파일 모니터링

```bash
# 로그 실시간 확인
tail -f application.log | grep "PII MASKING"
```
