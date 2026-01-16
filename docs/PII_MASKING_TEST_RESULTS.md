# PII 마스킹 테스트 결과

## 테스트 개요
대용량 JSON 처리 시 OLLAMA의 성능과 정확성을 테스트

## 테스트 결과

| 테스트 케이스 | 입력 크기 | 처리 시간 | 결과 크기 | 상태 |
|--------------|---------|-----------|-----------|------|
| Small JSON | 69 bytes | 920ms | 예상대로 | ✅ |
| Medium JSON (1KB) | 13KB | 11초 | 608 bytes | ⚠️ |
| Large JSON (10KB) | 133KB | 10초 | 401 bytes | ❌ |
| Very large JSON (40KB) | 541KB | 8.5초 | 523 bytes | ❌ |
| Huge JSON (100KB) | 1.3MB | 9.8초 | - | ⚠️ |

## 문제점

### 1. LLM의 Context Window 제한
- OLLAMA (qwen2.5)가 대용량 JSON을 전부 처리하지 못함
- 541KB 입력 → 523 bytes 출력 (1000배 압축)
- LLM이 JSON을 마스킹하지 않고 요약해버림

### 2. 처리 시간
- 1KB 이상: 8-11초 소요
- 실제 API 요청에선 타임아웃 발생 가능

### 3. 마스킹 정확성
- Small JSON: 정상 작동
- Large JSON: JSON 구조가 깨짐

## 해결 방안

### 옵션 1: 정규식 기반 마스킹 (권장)
```kotlin
// LLM 없이 직접 패턴 치환
fun maskJsonSimple(json: String): String {
    var result = json

    // user_id 마스킹
    result = result.replace(
        Regex("""user[_-]?id["\s:]+["']?([a-zA-Z0-9_\-]+)["']?"""),
        "user_id: \"[USER_ID]\""
    )

    // email 마스킹
    result = result.replace(
        Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""),
        "[EMAIL]"
    )

    return result
}
```

### 옵션 2: 특정 필드만 마스킹
```kotlin
// JSON 파싱 후 특정 필드만 마스킹
fun maskJsonSelective(json: String): String {
    val node = ObjectMapper().readTree(json)

    // metadata.user_id만 마스킹
    node.path("metadata")
         .path("user_id")
         .let { if (it.isTextual) (it as TextNode).text = "[USER_ID]" }

    return node.toString()
}
```

### 옵션 3: 청크 분할 처리
```kotlin
// 대용량 JSON을 청크로 나누어 처리
fun maskJsonChunked(json: String, chunkSize: Int = 5000): String {
    val chunks = json.chunked(chunkSize)
    return chunks.map { chunk ->
        // 각 청크를 OLLAMA에 전달
        maskWithOllama(chunk)
    }.joinToString("")
}
```

## 결론

현재 OLLAMA 기반 PII 마스킹은 **대용량 JSON에서 비실용적**입니다.

**권장 사항:**
1. 정규식 기반 마스킹 구현 (빠름, 정확)
2. 또는 특정 필드만 선택적 마스킹 (JSON 파싱 후 수정)
3. LLM은 복잡한 문맥 기반 마스킹에만 사용

## 다음 단계

1. 정규식 기반 마스킹 구현
2. 단위 테스트로 마스킹 정확성 검증
3. 프록시 서버에 통합
