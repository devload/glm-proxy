# GLM Proxy Server

CLAUDE CODE가 보내는 API 요청을 중간에서 가로채서 로깅하는 Spring Boot Proxy 서버입니다.

## 구조

```
CLAUDE CODE → Proxy Server (localhost:8080) → 실제 API 서버 (api.z.ai)
```

## 실행 방법

```bash
# 1. 서버 실행
./gradlew bootRun

# 또는
gradle bootRun
```

## CLAUDE CODE 설정

`~/.claude/settings.json` 파일의 `ANTHROPIC_BASE_URL`을 로컬 프록시 서버로 변경:

```json
{
  "env": {
    "ANTHROPIC_BASE_URL": "http://localhost:8080"
  }
}
```

## 로깅 확인

모든 Request와 Response가 콘솔에 로깅됩니다:

- **Request**: Method, Path, Query, Headers, Body
- **Response**: Status, Headers, Body, Duration

예시 로그:
```
================================================================================
REQUEST INCOMING
Timestamp: 2025-01-16T09:30:00.000Z
Method: POST
Path: /v1/messages
Query: {}
Headers:
  Authorization: [Bearer xxx]
  Content-Type: [application/json]
Body: {"model":"claude-sonnet-4-5","messages":[...]}
--------------------------------------------------------------------------------
Forwarding to: https://api.z.ai/api/anthropic/v1/messages
RESPONSE FROM TARGET
Status: 200 OK
Headers:
  Content-Type: [application/json]
Body: {"id":"msg_xxx","type":"message",...}
Duration: 1234ms
================================================================================
```

## 설정 변경

`src/main/resources/application.yml`에서 대상 서버 URL 변경 가능:

```yaml
target:
  base-url: https://api.z.ai/api/anthropic  # 여기를 변경
```
