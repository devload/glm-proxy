#!/bin/bash
# GLM Proxy Monitoring Stack 중지 스크립트

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=============================================="
echo "  Stopping GLM Proxy Monitoring Stack"
echo "=============================================="
echo ""

cd "$PROJECT_DIR"

# Docker Compose 중지
docker compose down

echo ""
echo "  All services stopped."
echo "=============================================="
