#!/usr/bin/env bash
set -euo pipefail

# 로컬 브라우저 실습용 단일 진입점.
# 인프라와 앱 compose 파일은 실행 수명과 데이터 볼륨을 구분하지만,
# 개발자는 이 스크립트로 하나의 스택처럼 올리고 내린다.
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

INFRA=(docker compose -p stove -f docker-compose.yml -f docker-compose.local.yml)
APPS=(docker compose -p stove-apps -f docker-compose.apps.yml -f docker-compose.apps.local.yml)

case "${1:-up}" in
  up)
    ./gradlew jibDockerBuild --no-daemon
    "${INFRA[@]}" up -d --no-build
    "${INFRA[@]}" wait mysql-bootstrap
    bash scripts/start-apps.sh "${APPS[@]:2}"
    python3 tools/verification/stack-status.py
    echo "로컬 스택이 시작되었습니다: http://127.0.0.1:18080/swagger-ui.html"
    ;;
  down)
    "${APPS[@]}" down
    "${INFRA[@]}" down
    ;;
  restart)
    "$0" down
    "$0" up
    ;;
  ps|status)
    "${INFRA[@]}" ps
    "${APPS[@]}" ps
    ;;
  logs)
    "${APPS[@]}" logs -f "${2:-gateway}"
    ;;
  *)
    echo "사용법: $0 {up|down|restart|ps|logs [서비스]}" >&2
    exit 2
    ;;
esac
