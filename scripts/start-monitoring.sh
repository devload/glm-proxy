#!/bin/bash
# GLM Proxy Monitoring Stack 시작 스크립트

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=============================================="
echo "  GLM Proxy Monitoring Stack"
echo "=============================================="
echo ""

cd "$PROJECT_DIR"

# Docker Compose 실행
echo "[1/3] Starting Jaeger, Prometheus, Grafana..."
docker compose up -d

echo ""
echo "[2/3] Waiting for services to be ready..."
sleep 5

# 서비스 상태 확인
echo ""
echo "[3/3] Checking service status..."
docker compose ps

echo ""
echo "=============================================="
echo "  Services Started Successfully!"
echo "=============================================="
echo ""
echo "  Jaeger UI:     http://localhost:16686"
echo "  Prometheus:    http://localhost:9090"
echo "  Grafana:       http://localhost:3000 (admin/admin)"
echo ""
echo "  OTLP HTTP:     http://localhost:4318"
echo "  OTLP gRPC:     http://localhost:4317"
echo ""
echo "=============================================="
echo ""
echo "Now start GLM Proxy application:"
echo "  ./gradlew bootRun"
echo ""
