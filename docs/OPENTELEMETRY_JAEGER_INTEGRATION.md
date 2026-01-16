# OpenTelemetry & Jaeger Integration Guide

GLM Proxy 서비스에서 OpenTelemetry를 사용하여 분산 추적(Distributed Tracing)을 구현하고, Jaeger를 통해 Request/Response 데이터와 이벤트를 모니터링하는 방법을 설명합니다.

## 목차

1. [아키텍처 개요](#아키텍처-개요)
2. [구성 요소](#구성-요소)
3. [빠른 시작](#빠른-시작)
4. [상세 설정](#상세-설정)
5. [Span 구조](#span-구조)
6. [Jaeger UI 사용법](#jaeger-ui-사용법)
7. [수집되는 데이터](#수집되는-데이터)
8. [문제 해결](#문제-해결)

---

## 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              GLM Proxy                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                          ProxyService                                │    │
│  │                                                                      │    │
│  │  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐    │    │
│  │  │ proxy_request│───│  pii_masking │───│ anthropic_api_request│    │    │
│  │  │    (Span)    │   │    (Span)    │   │        (Span)        │    │    │
│  │  └──────────────┘   └──────────────┘   └──────────────────────┘    │    │
│  │         │                  │                      │                 │    │
│  │         ▼                  ▼                      ▼                 │    │
│  │     [Tags]            [Tags]               [Tags/Events]            │    │
│  │   - http.method      - masking.duration   - api.response_bytes     │    │
│  │   - request.model    - masking.applied    - sse_events             │    │
│  │   - request.body_size                                               │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                    │                                         │
│                                    │ OTLP HTTP                               │
│                                    ▼                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Jaeger                                          │
│  ┌────────────────┐    ┌──────────────────┐    ┌───────────────────────┐   │
│  │ OTLP Collector │────│  Span Storage    │────│      Jaeger UI        │   │
│  │  (Port 4318)   │    │    (Memory)      │    │    (Port 16686)       │   │
│  └────────────────┘    └──────────────────┘    └───────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 구성 요소

### 1. OpenTelemetry SDK
- **Micrometer Tracing Bridge**: Spring Boot 3.x와 OpenTelemetry 연동
- **OTLP Exporter**: Trace 데이터를 Jaeger로 전송

### 2. Jaeger All-in-One
- **Collector**: OTLP 프로토콜로 trace 수신
- **Storage**: 메모리 저장소 (개발용)
- **Query/UI**: 웹 인터페이스로 trace 조회

### 3. 추가 모니터링 도구 (선택)
- **Prometheus**: 메트릭 수집
- **Grafana**: 대시보드 시각화

---

## 빠른 시작

### 1. Jaeger 시작

```bash
# 모니터링 스택 시작 (Jaeger만)
docker compose up -d jaeger

# 또는 전체 스택 시작 (Jaeger + Prometheus + Grafana)
docker compose up -d

# 또는 스크립트 사용
./scripts/start-monitoring.sh
```

### 2. 서비스 확인

- **Jaeger UI**: http://localhost:16686
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)

### 3. GLM Proxy 시작

```bash
./gradlew bootRun
```

### 4. Trace 확인

1. GLM Proxy로 API 요청 전송
2. Jaeger UI (http://localhost:16686) 접속
3. Service 드롭다운에서 "glm-proxy" 선택
4. "Find Traces" 클릭

---

## 상세 설정

### application.yml

```yaml
# OpenTelemetry & Tracing 설정
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0  # 100% 추적 (개발 환경)
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces

# OpenTelemetry OTLP Exporter 설정
otel:
  exporter:
    otlp:
      endpoint: http://localhost:4318
      protocol: http/protobuf
  service:
    name: glm-proxy
  traces:
    exporter: otlp
```

### build.gradle.kts 의존성

```kotlin
// OpenTelemetry & Micrometer Tracing
implementation("io.micrometer:micrometer-tracing-bridge-otel")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
implementation("io.opentelemetry:opentelemetry-sdk")
implementation("io.opentelemetry:opentelemetry-api")
implementation("io.opentelemetry:opentelemetry-sdk-trace")
implementation("io.micrometer:micrometer-registry-prometheus")
```

---

## Span 구조

GLM Proxy에서 생성하는 Span 계층 구조:

```
proxy_request (Parent Span)
├── Tags:
│   ├── http.method: POST
│   ├── http.url: /v1/messages
│   ├── http.path: /v1/messages
│   ├── request.model: claude-sonnet-4-5-20250929
│   ├── request.body_size: 1234
│   ├── pii.masking.enabled: true
│   ├── pii.masking.should_process: true
│   ├── http.status_code: 200
│   ├── proxy.duration_ms: 5432
│   └── response.total_bytes: 8765
│
├── Events:
│   ├── request_received
│   ├── sse_stream_subscribed
│   ├── sse_event: message_start
│   ├── sse_event: content_block_delta
│   └── streaming_completed
│
├── pii_masking (Child Span) [PII 마스킹 활성화 시]
│   ├── Tags:
│   │   ├── masking.enabled: true
│   │   ├── masking.body_size: 1234
│   │   ├── masking.duration_ms: 234
│   │   ├── masking.applied: true
│   │   └── masking.masked_body_size: 1200
│   └── Events:
│       ├── ollama_processing_started
│       └── ollama_processing_completed
│
└── anthropic_api_request (Child Span)
    ├── Tags:
    │   ├── api.url: https://api.anthropic.com/v1/messages
    │   ├── api.method: POST
    │   └── api.response_bytes: 8765
    └── Events:
        ├── api_response_started
        └── api_response_completed
```

---

## Jaeger UI 사용법

### Trace 검색

1. **Service**: `glm-proxy` 선택
2. **Operation**: 특정 작업 필터링
   - `proxy_request`: 전체 요청
   - `pii_masking`: PII 마스킹 처리
   - `anthropic_api_request`: API 호출
3. **Tags**: 특정 조건 검색
   - `http.status_code=200`: 성공 요청만
   - `error=true`: 에러 요청만
   - `pii.masking.applied=true`: PII 마스킹 적용된 요청

### Trace 상세 보기

각 Trace를 클릭하면:
- **Timeline View**: 각 Span의 시간 관계
- **Tags**: 수집된 모든 태그 정보
- **Logs/Events**: 발생한 이벤트 목록
- **Process**: 서비스 정보

### 검색 예시

```
# PII 마스킹이 적용된 요청만 조회
service=glm-proxy pii.masking.applied=true

# 에러가 발생한 요청만 조회
service=glm-proxy error=true

# 특정 모델 요청 조회
service=glm-proxy request.model=claude-opus-4-5-20250929
```

---

## 수집되는 데이터

### Request 데이터

| Tag | 설명 | 예시 |
|-----|------|------|
| http.method | HTTP 메소드 | POST |
| http.url | 요청 URL | /v1/messages |
| http.path | 경로 | /v1/messages |
| http.query | 쿼리 파라미터 | {} |
| request.model | 요청 모델 | claude-sonnet-4-5-20250929 |
| request.body_size | 요청 바디 크기 | 1234 |

### PII Masking 데이터

| Tag | 설명 | 예시 |
|-----|------|------|
| masking.enabled | 마스킹 활성화 여부 | true |
| masking.body_size | 원본 바디 크기 | 1234 |
| masking.duration_ms | 마스킹 처리 시간 | 234 |
| masking.applied | 마스킹 적용 여부 | true |
| masking.masked_body_size | 마스킹 후 바디 크기 | 1200 |

### Response 데이터

| Tag | 설명 | 예시 |
|-----|------|------|
| http.status_code | HTTP 상태 코드 | 200 |
| response.message_id | 응답 메시지 ID | msg_01... |
| response.stop_reason | 종료 이유 | end_turn |
| response.output_tokens | 출력 토큰 수 | 42 |
| response.total_bytes | 총 응답 바이트 | 8765 |
| proxy.duration_ms | 총 처리 시간 | 5432 |

### SSE 이벤트

모든 SSE 이벤트가 Span Event로 기록됩니다:
- `message_start`
- `content_block_start`
- `content_block_delta`
- `content_block_stop`
- `message_delta`
- `message_stop`

---

## 문제 해결

### Jaeger에 trace가 나타나지 않음

1. **Jaeger 실행 확인**
   ```bash
   docker compose ps
   curl http://localhost:16686/api/services
   ```

2. **OTLP 엔드포인트 확인**
   ```bash
   curl -v http://localhost:4318/v1/traces
   ```

3. **application.yml 설정 확인**
   - `management.tracing.enabled: true`
   - `management.otlp.tracing.endpoint` 확인

4. **로그 확인**
   ```bash
   docker compose logs jaeger
   ```

### Trace가 끊어져 보임

- 비동기 처리에서 Context 전파 확인
- Reactor/WebFlux 환경에서 `tracer.nextSpan(parentSpan)` 사용 확인

### 메모리 사용량 증가

개발 환경에서는 메모리 저장소를 사용하므로 오래된 trace는 자동 삭제됩니다.
운영 환경에서는 Elasticsearch 등 외부 저장소 사용을 권장합니다.

```yaml
# docker-compose.yml - Elasticsearch 사용 시
environment:
  - SPAN_STORAGE_TYPE=elasticsearch
  - ES_SERVER_URLS=http://elasticsearch:9200
```

---

## 관련 문서

- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [Spring Boot Observability](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.observability)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)
