#!/bin/bash

# Anthropic API 실제 응답 캡처 스크립트
# API 키가 필요합니다

echo "========================================================================"
echo "🔍 CAPTURING REAL ANTHROPIC API SSE RESPONSE"
echo "========================================================================"
echo ""

API_KEY="${ANTHROPIC_API_KEY:-}"

if [ -z "$API_KEY" ]; then
    echo "❌ ANTHROPIC_API_KEY environment variable not set"
    echo "   Please set it with: export ANTHROPIC_API_KEY='your-key-here'"
    echo ""
    echo "   Or run with:"
    echo "   ANTHROPIC_API_KEY='your-key' bash capture_real_api.sh"
    echo ""
    exit 1
fi

echo "✅ API Key found (length: ${#API_KEY})"
echo ""
echo "📤 Sending request to Anthropic API..."
echo ""

# 실제 API에 요청
curl -N https://api.anthropic.com/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: $API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -d '{
    "model": "claude-haiku-4-5-202501001",
    "max_tokens": 100,
    "messages": [
      {
        "role": "user",
        "content": "Hello, please say hi back"
      }
    ]
  }' 2>&1 | tee /tmp/anthropic_sse_response.log

echo ""
echo "========================================================================"
echo "📊 RESPONSE ANALYSIS"
echo "========================================================================"
echo ""

# 응답 분석
echo "🎯 EVENT TYPES FOUND:"
grep -E "^event: " /tmp/anthropic_sse_response.log | sort | uniq -c | while read count event; do
    echo "   $count: $event"
done

echo ""
echo "📄 FIRST 20 EVENTS:"
head -100 /tmp/anthropic_sse_response.log | sed 's/^/   /'

echo ""
echo "💾 Full response saved to: /tmp/anthropic_sse_response.log"
echo "========================================================================"
