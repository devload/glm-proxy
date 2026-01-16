# CLAUDE CODE Proxy 서버 데이터 흐름 분석

## 개요

본 문서는 Spring Boot로 구현된 Proxy 서버를 통해 CLAUDE CODE가 API 요청을 보내고 응답을 받는 전체 데이터 흐름을 실제 로그를 통해 분석한 것이다.

## 아키텍처

```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│ CLAUDE CODE │ ──────> │  Proxy 서버  │ ──────> │ api.z.ai    │
│   (Client)  │ <────── │ (localhost:  │ <────── │  (실제 서버) │
└─────────────┘         │   8080)      │         └─────────────┘
                        └──────────────┘
                            로깅 및
                          포워딩 처리
```

## 실제 요청 분석

### 1. 사용자 입력

**사용자:** "지금 너의 claude는 proxy를 통해서 체크중이야"

### 2. CLAUDE CODE → Proxy 서버 (Request)

#### HTTP Request 정보

```http
POST /v1/messages?beta=true HTTP/1.1
Host: localhost:8080
Accept: application/json
Content-Type: application/json
Authorization: Bearer 53908b64385d4fb2a57aeea1720a4dac.1PfEj3H38GWVv20h
User-Agent: claude-cli/2.1.2 (external, cli)
x-stainless-helper-method: stream
x-stainless-runtime: node
x-stainless-runtime-version: v24.3.0
x-app: cli
x-stainless-arch: arm64
x-stainless-lang: js
x-stainless-os: MacOS
x-stainless-package-version: 0.70.0
x-stainless-timeout: 3000
x-stainless-retry-count: 0
anthropic-version: 2023-06-01
anthropic-beta: claude-code-20250219,interleaved-thinking-2025-05-14
anthropic-dangerous-direct-browser-access: true
Connection: keep-alive
Accept-Encoding: gzip, deflate, br, zstd
Content-Length: 918
```

#### Request Body (JSON)

```json
{
  "model": "claude-haiku-4-5-20251001",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "지금 너의 claude는 proxy를 통해서 체크중이야"
        }
      ]
    },
    {
      "role": "assistant",
      "content": [
        {
          "type": "text",
          "text": "{"
        }
      ]
    }
  ],
  "system": [
    {
      "type": "text",
      "text": "You are Claude Code, Anthropic's official CLI for Claude."
    },
    {
      "type": "text",
      "text": "Analyze if this message indicates a new conversation topic..."
    }
  ],
  "tools": [],
  "metadata": {
    "user_id": "user_9911a7f2646c899166c79a717d612c589ef112c08a8d12b61e0e4bca3c14b4e3_account__session_c1cd8547-22a2-4928-9c48-96688f409afa"
  },
  "max_tokens": 32000,
  "stream": true
}
```

### 3. Proxy 서버 처리

#### 로깅 정보

```
[2026-01-16 09:47:22.667] REQUEST INCOMING
Timestamp: 1768524442679
Method: POST
Path: /v1/messages
Query: {beta=[true]}
```

#### 헤더 필터링

Proxy 서버는 다음 헤더들을 제거하고 나머지만 전달:

- ❌ **Host**: `localhost:8080` → 제거 (WebClient가 자동으로 `api.z.ai`로 설정)
- ❌ **Content-Length**: `918` → 제거 (WebClient가 자동으로 계산)
- ❌ **Connection**: `keep-alive` → 제거 (연결 관리는 WebClient가 담당)
- ✅ **Authorization**: 전달
- ✅ **Content-Type**: 전달
- ✅ **anthropic-***: 모든 Anthropic 관련 헤더 전달

#### 포워딩

```
Forwarding to: https://api.z.ai/api/anthropic/v1/messages?beta=true
```

### 4. Proxy 서버 → api.z.ai (실제 요청)

```http
POST /v1/messages?beta=true HTTP/1.1
Host: api.z.ai
Authorization: Bearer 53908b64385d4fb2a57aeea1720a4dac.1PfEj3H38GWVv20h
Content-Type: application/json
anthropic-version: 2023-06-01
anthropic-beta: claude-code-20250219,interleaved-thinking-2025-05-14
... (나머지 헤더)
```

### 5. api.z.ai → Proxy 서버 (Response)

#### Response Headers

```http
HTTP/1.1 200 OK
Server: nginx
Date: Fri, 16 Jan 2026 00:47:24 GMT
Content-Type: text/event-stream; charset=utf-8
Transfer-Encoding: chunked
Connection: keep-alive
Vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers
X-LOG-ID: 20260116084723bc184d1424ba4056
cache-control: no-cache
access-control-allow-origin: *
access-control-allow-headers: *
x-process-time: 0.03499889373779297
Strict-Transport-Security: max-age=31536000; includeSubDomains
```

#### Response Body (Server-Sent Events Stream)

```
event: message_start
data: {"type": "message_start", "message": {"id": "msg_20260116084723bc184d1424ba4056", ...}}

event: ping
data: {"type": "ping"}

event: content_block_start
data: {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "{\n    \""}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "isNewTopic\": true"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": ",\n    \"title"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "\": \"설정"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": " 점검"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "\"\n}"}}

event: content_block_stop
data: {"type": "content_block_stop", "index": 0}

event: message_delta
data: {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": null}, "usage": {"input_tokens": 53, "output_tokens": 40, ...}}

event: message_stop
data: {"type": "message_stop"}
```

### 6. Proxy 서버 로깅

```
[2026-01-16 09:47:24.879] RESPONSE FROM TARGET
Status: 200 OK
Headers:
  Server: [nginx]
  Content-Type: [text/event-stream; charset=utf-8]
  X-LOG-ID: [20260116084723bc184d1424ba4056]
  ...
Body: [전체 SSE 스트림 로깅]
Duration: 2213ms
```

### 7. Proxy 서버 → CLAUDE CODE (Response 전달)

```
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=utf-8
[Response Body 전달]
```

## 시간 분석

| 단계 | 시간 (ms) | 비고 |
|------|----------|------|
| Request 수신 | 0 | 시작 |
| Proxy 처리 | ~10 | 헤더 필터링, 로깅 |
| api.z.ai 응답 시간 | 2203 | 실제 API 처리 시간 |
| **총 소요 시간** | **2213** | end-to-end |

## 데이터 포맷

### Request Format
- **Type**: HTTP POST
- **Content-Type**: application/json
- **Body**: JSON (model, messages, system, tools, metadata 등)
- **Streaming**: true (응답을 스트림으로 받음)

### Response Format
- **Type**: Server-Sent Events (SSE)
- **Content-Type**: text/event-stream; charset=utf-8
- **Format**: Event-driven stream
  - `message_start`: 메시지 시작
  - `content_block_start`: 컨텐츠 블록 시작
  - `content_block_delta`: 컨텐츠 델타 (실제 텍스트)
  - `content_block_stop`: 컨텐츠 블록 종료
  - `message_delta`: 메시지 메타데이터 (usage 등)
  - `message_stop`: 메시지 종료

## Proxy 서버 핵심 기능

### 1. 헤더 필터링
```kotlin
when (key.lowercase()) {
    "host" -> {} // 제거: WebClient가 자동으로 설정
    "content-length" -> {} // 제거: WebClient가 자동으로 계산
    "connection" -> {} // 제거: WebClient가 담당
    "transfer-encoding" -> {} // 제거: WebClient가 담당
    else -> targetHeaders[key] = values // 나머지는 전달
}
```

### 2. 로깅
- Request: 모든 헤더, 바디, 쿼리 파라미터
- Response: 상태 코드, 헤더, 바디, 소요 시간
- Body가 2000자를 넘으면 자동으로 truncation

### 3. 포워딩
- 동일한 HTTP method 유지
- 모든 path와 query parameter 유지
- 필터링된 헤더 전달
- Request body 그대로 전달

## 보안 고려사항

### Authorization Token
```json
"authorization": "[Bearer 53908b64385d4fb2a57aeea1720a4dac.1PfEj3H38GWVv20h]"
```
- 실제 로그에 token이 그대로 노출됨
- 운영 환경에서는 token 마스킹 필요

### 민감 정보
- `user_id`: 세션 식별자 포함
- `anthropic-beta`: 베타 기능 식별자
- 모든 요청/응답이 로그에 저장됨

## 성능 지표

| 메트릭 | 값 |
|--------|-----|
| 평균 응답 시간 | ~2.2초 |
| Request Body 크기 | 918 bytes |
| Response Body 크기 | ~1.5 KB (SSE stream) |
| Input Tokens | 53 |
| Output Tokens | 40 |

## 결론

Proxy 서버가 성공적으로:
1. ✅ 모든 Request를 로깅
2. ✅ 헤더를 적절히 필터링하여 403 에러 해결
3. ✅ 실제 서버로 안전하게 포워딩
4. ✅ 모든 Response를 로깅
5. ✅ 스트리밍 응답 처리
6. ✅ end-to-end latency 측정

**총 처리 시간: 2.2초** (API 서버 처리 시간 포함)
