# OLLAMA PII 마스킹 기능 설치 가이드

## 현재 상태

프록시 서버에 **OLLAMA 기반 PII 마스킹 기능**이 구현되었습니다!

## 구현된 기능

1. ✅ **Spring AI 통신 완료**
   - `spring-ai-ollama-spring-boot-starter` 의존성 추가
   - `PIIMaskingService` 구현
   - `ProxyService`에 마스킹 처리 통합

2. ✅ **마스킹 로직**
   - Authorization token → `[REDACTED_TOKEN]`
   - User ID → `[USER_ID]`
   - Email → `[EMAIL]`
   - File paths → `/Users/[USER]/...`

3. ✅ **설정**
   ```yaml
   pii:
     masking:
       enabled: true  # 마스킹 활성화
   spring:
     ai:
       ollama:
         base-url: http://localhost:11434
         chat:
           options:
             model: llama3.2
   ```

## OLLAMA 설치가 필요합니다

### 1. OLLAMA 설치

```bash
# macOS
curl -fsSL https://ollama.com/install.sh | sh

# Linux
curl -fsSL https://ollama.com/install.sh | sh
```

### 2. 모델 다운로드

```bash
# Llama 3.2 모델 (권장)
ollama pull llama3.2

# 또는 Phi-3 (더 가벼움)
ollama pull phi3
```

### 3. OLLAMA 실행

```bash
# OLLAMA 서버 시작
ollama serve
```

## 설치 확인

OLLAMA가 실행 중인지 확인:

```bash
curl http://localhost:11434/api/tags
```

응답이 오면 설치 성공!

## 사용 방법

### 1. OLLAMA 실행
```bash
ollama serve
```

### 2. 프록시 서버 실행
```bash
cd /Users/rohsunghyun/glmAlaysis
./gradlew bootRun
```

### 3. PII 마스킹 테스트

CLAUDE CODE에서 메시지를 보내면:
```
🔒 PII Masking ENABLED - Processing with OLLAMA...
🔒 PII Masking COMPLETED
Body (Original): {"authorization":"Bearer 53908b64385d4fb2a57aeea1720a4dac..."}
Body (Masked): {"authorization":"[REDACTED_TOKEN]"}
```

## OLLAMA 설치 후 테스트

```bash
# 1. OLLAMA 실행
ollama serve

# 2. 다른 터미널에서 테스트
curl http://localhost:11434/api/generate -d '{
  "model": "llama3.2",
  "prompt": "Mask this email: test@example.com and token: abc123"
}'
```

## PII 마스킹 비교

### 마스킹 전
```json
{
  "authorization": "Bearer 53908b64385d4fb2a57aeea1720a4dac.1PfEj3H38GWVv20h",
  "user_id": "user_9911a7f2646c899166c79a717d612c589ef112c08a8d12b61e0e4bca3c14b4e3",
  "email": "test@example.com"
}
```

### 마스킹 후
```json
{
  "authorization": "[REDACTED_TOKEN]",
  "user_id": "[USER_ID]",
  "email": "[EMAIL]"
}
```

## 기술 스택

- **Spring AI 1.0.0-M4**: OLLAMA 통신
- **WebFlux + Reactor**: 비동기 처리
- **ChatClient**: LLM 호출
- **Llama 3.2**: 로컬 LLM (또는 Phi-3)

## 다음 단계

1. **OLLAMA 설치** (위 가이드 참조)
2. **프록시 서버 실행**
3. **마스킹 테스트**
4. **문서화**: 마스킹 전후 비교

---

**참고**: Spring AI는 OLLAMA를 공식 지원하며, ChatClient를 통해 간단하게 LLM을 호출할 수 있습니다!
