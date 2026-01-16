# CLAUDE CODE API 데이터 포맷 및 카테고리 분석

## 개요

본 문서는 Proxy 서버를 통해 수집된 실제 API 요청/응답 데이터를 분석하여 데이터 포맷과 카테고리를 체계화한다.

## 데이터 카테고리 구조

```
📦 API 데이터
├── 🔐 인증/보안 (Authentication & Security)
├── 📋 메타데이터 (Metadata)
├── 💬 대화 컨텐츠 (Conversation Content)
├── ⚙️ 시스템 설정 (System Configuration)
├── 📊 성능/테크닉 (Performance & Technical)
└── 🎤 기능/기능 플래그 (Features & Flags)
```

---

## 1. REQUEST 데이터 분석

### 1.1 HTTP 레벨 데이터

#### 헤더 (Headers)

**카테고리: 🔐 인증/보안**
```http
Authorization: Bearer 53908b64385d4fb2a57aeea1720a4dac.1PfEj3H38GWVv20h
```
- **포맷**: `Bearer <token>`
- **용도**: API 인증
- **민감도**: ⚠️ 높음 (마스킹 필요)

**카테고리: 📋 클라이언트 정보 (Client Metadata)**
```http
User-Agent: claude-cli/2.1.2 (external, cli)
x-app: cli
x-stainless-arch: arm64
x-stainless-lang: js
x-stainless-os: MacOS
x-stainless-runtime: node
x-stainless-runtime-version: v24.3.0
x-stainless-package-version: 0.70.0
```
- **포맷**: 키-값 쌍 (string)
- **용도**: 클라이언트 식별 및 호환성
- **데이터 타입**:
  - `User-Agent`: 버전 문자열
  - `x-app`: 앱 유형 ("cli")
  - `x-stainless-arch`: 아키텍처 ("arm64", "x64")
  - `x-stainless-os`: 운영체제 ("MacOS", "Windows", "Linux")
  - `x-stainless-runtime`: 런타임 환경 ("node", "browser")
  - `x-stainless-runtime-version`: 런타임 버전

**카테고리: 🎤 기능 플래그 (Feature Flags)**
```http
anthropic-version: 2023-06-01
anthropic-beta: claude-code-20250219,interleaved-thinking-2025-05-14
anthropic-dangerous-direct-browser-access: true
x-stainless-helper-method: stream
```
- **포맷**: 쉼표로 구분된 리스트 또는 boolean
- **용도**: 베타 기능 활성화, API 버전 관리
- **데이터 타입**:
  - `anthropic-version`: 날짜 형식 (YYYY-MM-DD)
  - `anthropic-beta`: 쉼표로 구분된 기능 이름
  - `anthropic-dangerous-direct-browser-access`: boolean

**카테고리: 📊 성능/테크닉 (Performance & Technical)**
```http
x-stainless-timeout: 3000
x-stainless-retry-count: 0
Connection: keep-alive
Accept-Encoding: gzip, deflate, br, zstd
```
- **포맷**: 숫자, 리스트
- **용도**: 타임아웃, 재시도, 압축 설정
- **데이터 타입**:
  - `x-stainless-timeout`: milliseconds (숫자)
  - `x-stainless-retry-count`: 재시도 횟수 (숫자)

**카테고리: ⚙️ 콘텐츠 협상 (Content Negotiation)**
```http
Accept: application/json
Content-Type: application/json
```
- **포맯**: MIME 타입
- **용도**: 데이터 형식 지정

### 1.2 Request Body 데이터

**포맷**: JSON

#### 카테고리: ⚙️ 모델 설정 (Model Configuration)

```json
{
  "model": "claude-haiku-4-5-20251001",  // 또는 "claude-sonnet-4-5-20250929"
  "max_tokens": 32000,                    // 최대 출력 토큰 수
  "stream": true                          // 스트리밍 응답 여부
}
```

**데이터 타입 분석**:
- `model`: 문자열 (식별자)
  - 패턴: `claude-{모델명}-{버전}`
  - 예: `claude-haiku-4-5-20251001`, `claude-sonnet-4-5-20250929`
- `max_tokens`: 정수 (0 ~ 32000)
- `stream`: 불리언 (true/false)

#### 카테고리: 💬 대화 컨텐츠 (Conversation Content)

```json
{
  "messages": [
    {
      "role": "user",           // 역할: "user", "assistant", "system"
      "content": [              // 멀티모달 컨텐츠 배열
        {
          "type": "text",       // 컨텐츠 타입: "text", "image", "tool_use", "tool_result"
          "text": "..."         // 실제 텍스트 내용
        },
        {
          "type": "thinking",
          "thinking": "...",    // thinking 모드 내용
          "signature": "..."    // thinking 서명
        }
      ]
    }
  ]
}
```

**컨텐츠 타입 분석**:
| 타입 | 설명 | 데이터 포맷 |
|------|------|-----------|
| `text` | 일반 텍스트 | string |
| `thinking` | 생각 추적 | {thinking: string, signature: string} |
| `tool_use` | 도구 사용 | {id: string, name: string, input: object} |
| `tool_result` | 도구 결과 | {tool_use_id: string, content: array} |
| `image` | 이미지 | {type: "image", source: {type: url/base64, data: string}} |

**role 타입**:
- `user`: 사용자 입력
- `assistant`: AI 응답
- `system`: 시스템 프롬프트

#### 카테고리: 📋 시스템 프롬프트 (System Prompt)

```json
{
  "system": [
    {
      "type": "text",
      "text": "You are Claude Code, Anthropic's official CLI for Claude."
    },
    {
      "type": "text",
      "text": "Analyze if this message indicates a new conversation topic..."
    }
  ]
}
```
- **포맷**: 객체 배열
- **용도**: AI 동작 정의, 컨텍스트 설정
- **데이터 타입**: 다중 텍스트 블록

#### 카테고리: 🔧 도구 (Tools)

```json
{
  "tools": []
}
```
- **포맯**: 객체 배열
- **용도**: 사용 가능한 도구 정의
- **도구 타입**:
  - `function`: 함수 호출
  - `computer_20241022`: 컴퓨터 제어
  - `text_editor_20241022`: 텍스트 에디터
  - `bash_20241022`: bash 명령 실행

#### 카테고리: 📋 메타데이터 (Metadata)

```json
{
  "metadata": {
    "user_id": "user_9911a7f2646c899166c79a717d612c589ef112c08a8d12b61e0e4bca3c14b4e3_account__session_c1cd8547-22a2-4928-9c48-96688f409afa"
  }
}
```
- **포맷**: 키-값 쌍
- **용도**: 사용자/세션 식별
- **데이터 구조**:
  - `user_id`: `{계정ID}_session_{세션ID}` 형식

---

## 2. RESPONSE 데이터 분석

### 2.1 HTTP 레벨 데이터

#### 헤더 (Headers)

**카테고리: 📊 서버 정보 (Server Information)**
```http
Server: nginx
Date: Fri, 16 Jan 2026 00:47:24 GMT
X-LOG-ID: 20260116084723bc184d1424ba4056
x-process-time: 0.03499889373779297
```
- **포맷**: 문자열, 날짜, UUID
- **용도**: 서버 식별, 로깅, 성능 모니터링

**카테고리: ⚙️ 콘텐츠 협상 (Content Negotiation)**
```http
Content-Type: text/event-stream; charset=utf-8
Transfer-Encoding: chunked
```
- **포맯**: MIME 타입 + 파라미터
- **용도**: 스트리밍 응답 형식 지정

**카테고리: 🔐 보안 (Security)**
```http
Strict-Transport-Security: max-age=31536000; includeSubDomains
cache-control: no-cache
access-control-allow-origin: *
access-control-allow-headers: *
```
- **포맯**: 지시어 + 파라미터
- **용도**: HTTPS 강제, CORS, 캐싱 제어

### 2.2 Response Body 데이터 (Server-Sent Events)

**포맷**: Server-Sent Events (SSE)

#### 이벤트 타입별 카테고리

**카테고리: 📦 메시지 라이프사이클 (Message Lifecycle)**

```http
event: message_start
data: {
  "type": "message_start",
  "message": {
    "id": "msg_20260116084723bc184d1424ba4056",
    "type": "message",
    "role": "assistant",
    "model": "glm-4.5-air",
    "content": [],
    "stop_reason": null,
    "stop_sequence": null,
    "usage": {
      "input_tokens": 0,
      "output_tokens": 0
    }
  }
}
```
- **이벤트**: `message_start`
- **데이터**: 메시지 메타데이터
- **필드**:
  - `id`: 고유 메시지 ID
  - `type": "message"`
  - `role`: "assistant"
  - `model`: 사용된 모델
  - `stop_reason`: null (진행 중)
  - `usage`: 토큰 사용량 (초기값: 0)

```http
event: message_stop
data: {
  "type": "message_stop"
}
```
- **이벤트**: `message_stop`
- **용도**: 메시지 완료 신호

**카테고리: 💬 컨텐츠 블록 (Content Blocks)**

```http
event: content_block_start
data: {
  "type": "content_block_start",
  "index": 0,
  "content_block": {
    "type": "text",
    "text": ""
  }
}
```
- **이벤트**: `content_block_start`
- **데이터**: 컨텐츠 블록 시작
- **필드**:
  - `index`: 블록 순서 (0, 1, 2, ...)
  - `content_block.type`: 컨텐츠 타입 ("text", "thinking", "tool_use")

```http
event: content_block_delta
data: {
  "type": "content_block_delta",
  "index": 0,
  "delta": {
    "type": "text_delta",
    "text": "{\n    \""
  }
}
```
- **이벤트**: `content_block_delta`
- **데이터**: 증분 컨텐츠 (스트리밍)
- **필드**:
  - `delta.type`: 델타 타입 ("text_delta", "thinking_delta")
  - `delta.text`: 실제 텍스트 조각

```http
event: content_block_stop
data: {
  "type": "content_block_stop",
  "index": 0
}
```
- **이벤트**: `content_block_stop`
- **용도**: 컨텐츠 블록 완료

**카테고리: 📊 메타데이터/사용량 (Metadata & Usage)**

```http
event: message_delta
data: {
  "type": "message_delta",
  "delta": {
    "stop_reason": "end_turn",
    "stop_sequence": null
  },
  "usage": {
    "input_tokens": 53,
    "output_tokens": 40,
    "cache_read_input_tokens": 163,
    "server_tool_use": {
      "web_search_requests": 0
    },
    "service_tier": "standard"
  }
}
```
- **이벤트**: `message_delta`
- **데이터**: 최종 사용량 및 종료 이유
- **필드**:
  - `delta.stop_reason`: 종료 사유
    - `"end_turn"`: 정상 완료
    - `"max_tokens"`: 토큰 한도 도달
    - `"stop_sequence"`: 중지 시퀀스 감지
    - `"tool_use"`: 도구 사용
  - `usage.input_tokens`: 입력 토큰 수
  - `usage.output_tokens`: 출력 토큰 수
  - `usage.cache_read_input_tokens`: 캐시된 토큰
  - `usage.server_tool_use`: 서버 측 도구 사용량
  - `usage.service_tier`: 서비스 등급

**카테고리: 🔔 핑 (Ping)**

```http
event: ping
data: {"type": "ping"}
```
- **이벤트**: `ping`
- **용도**: 연결 유지 (keep-alive)

---

## 3. 데이터 카테고리 매핑 테이블

### 3.1 Request 데이터 카테고리

| 카테고리 | 데이터 필드 | 타입 | 예시 | 민감도 |
|---------|-----------|------|------|--------|
| **🔐 인증** | Authorization | string | `Bearer xxx...` | ⚠️ 높음 |
| **📋 클라이언트 정보** | User-Agent, x-app, x-stainless-* | string | `claude-cli/2.1.2` | 보통 |
| **🎤 기능 플래그** | anthropic-beta, anthropic-version | string, list | `interleaved-thinking-2025-05-14` | 낮음 |
| **📊 성능** | x-stainless-timeout, x-stainless-retry-count | number | `3000`, `0` | 낮음 |
| **⚙️ 모델 설정** | model, max_tokens, stream | string, number, boolean | `claude-sonnet-4-5-20250929` | 낮음 |
| **💬 대화** | messages[] | array | `[{role, content}]` | 높음 |
| **📋 메타데이터** | metadata.user_id | string | `user_xxx_session_yyy` | ⚠️ 높음 |
| **🔧 도구** | tools[] | array | `[{name, input}]` | 보통 |

### 3.2 Response 데이터 카테고리

| 카테고리 | 이벤트 타입 | 데이터 필드 | 타입 | 예시 |
|---------|-----------|-----------|------|------|
| **📦 라이프사이클** | message_start, message_stop | id, type, role | string | `msg_xxx` |
| **💬 컨텐츠** | content_block_* | index, delta | number, object | `0`, `{text: "..."}` |
| **📊 사용량** | message_delta | usage.*, stop_reason | object, string | `{input_tokens: 53}` |
| **🔔 핑** | ping | type | string | `ping` |

---

## 4. 데이터 포맷 요약

### 4.1 Request 포맷

```yaml
포맷: HTTP POST + JSON
Content-Type: application/json
구조:
  headers: {
    authentication: "Bearer token"
    client_info: {app, arch, os, runtime, version}
    features: [beta_features]
    performance: {timeout, retry_count}
  }
  body: {
    model: string
    max_tokens: number
    stream: boolean
    messages: [{role, content: [{type, text/thinking/image}]}]
    system: [{type, text}]
    tools: [{name, description, input_schema}]
    metadata: {user_id}
  }
```

### 4.2 Response 포맷

```yaml
포맷: Server-Sent Events (SSE)
Content-Type: text/event-stream; charset=utf-8
구조:
  event_stream: [
    {event: "message_start", data: {id, type, role, model, usage}},
    {event: "content_block_start", data: {index, content_block}},
    {event: "content_block_delta", data: {index, delta: {type, text}}},
    ... (여러 delta 이벤트)
    {event: "content_block_stop", data: {index}},
    {event: "message_delta", data: {delta: {stop_reason}, usage}},
    {event: "message_stop", data: {}}
  ]
```

---

## 5. 데이터 추출 전략

### 5.1 로깅 시점별 데이터 추출

**Request 로깅 시점:**
```json
{
  "timestamp": 1768524442679,
  "method": "POST",
  "path": "/v1/messages",
  "query": {beta: ["true"]},
  "headers": {...},
  "body": {...}
}
```

**Response 로깅 시점:**
```json
{
  "status": 200,
  "headers": {...},
  "body": "[SSE stream]",
  "duration": 2213
}
```

### 5.2 카테고리별 추출 우선순위

| 우선순위 | 카테고리 | 추출 항목 | 용도 |
|---------|---------|----------|------|
| 1 | 💬 대화 | messages[].content[].text | 사용자 질문 분석 |
| 2 | 📊 사용량 | usage.* | 비용/토큰 모니터링 |
| 3 | ⚙️ 모델 | model, max_tokens | 모델 사용 패턴 |
| 4 | 🔐 인증 | user_id (from metadata) | 사용자 식별 |
| 5 | 📊 성능 | duration, x-process-time | 성능 모니터링 |
| 6 | 🎤 기능 | anthropic-beta | 베타 기능 사용 |
| 7 | 🔧 도구 | tools[], content[].tool_use | 도구 사용 분석 |

---

## 6. 실제 예시: 완전한 Request-Response 쌍

### Request
```json
{
  "model": "claude-haiku-4-5-20251001",
  "messages": [
    {
      "role": "user",
      "content": [{"type": "text", "text": "지금 너의 claude는 proxy를 통해서 체크중이야"}]
    }
  ],
  "system": [
    {"type": "text", "text": "You are Claude Code..."}
  ],
  "tools": [],
  "metadata": {
    "user_id": "user_9911..._session_c1cd..."
  },
  "max_tokens": 32000,
  "stream": true
}
```

### Response (SSE Stream)
```
event: message_start
data: {"type": "message_start", "message": {"id": "msg_xxx", ...}}

event: content_block_start
data: {"type": "content_block_start", "index": 0, "content_block": {"type": "text"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "네"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": ", 정확합니다!"}}

event: content_block_stop
data: {"type": "content_block_stop", "index": 0}

event: message_delta
data: {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"input_tokens": 53, "output_tokens": 40}}

event: message_stop
data: {"type": "message_stop"}
```

---

## 7. 결론

### 데이터 카테고리 요약

1. **🔐 인증/보안**: Authorization 토큰 (민감 데이터)
2. **📋 클라이언트 정보**: 버전, OS, 런타임 정보
3. **🎤 기능 플래그**: 베타 기능, API 버전
4. **📊 성능**: 타임아웃, 재시도, 처리 시간
5. **⚙️ 모델 설정**: 모델명, 토큰 제한, 스트리밍
6. **💬 대화 컨텐츠**: 사용자/어시스턴트 메시지, 멀티모달 컨텐츠
7. **🔧 도구**: 사용 가능한 도구 정의
8. **📋 메타데이터**: 사용자 ID, 세션 ID

### 주요 데이터 포맷

- **Request**: HTTP POST + JSON
- **Response**: Server-Sent Events (SSE) stream
- **Content-Type**: application/json (request), text/event-stream (response)
- **인코딩**: UTF-8

### 추천 분석 방향

1. **대화 분석**: messages[].content[].text 추출
2. **사용량 추적**: usage.* 필드 모니터링
3. **성능 모니터링**: duration, x-process-time 추적
4. **사용자 식별**: metadata.user_id 패턴 분석
5. **기능 사용**: anthropic-beta, tools[] 사용 패턴
