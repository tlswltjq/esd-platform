#!/usr/bin/env bash
set -euo pipefail

# 로컬 브라우저 실습용 단일 진입점.
# 인프라와 앱 compose 파일은 CI 격리를 위해 내부적으로 분리되어 있지만,
# 개발자는 이 스크립트로 하나의 스택처럼 올리고 내린다.
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

INFRA=(docker compose -p stove -f docker-compose.yml)
APPS=(docker compose -p stove-apps -f docker-compose.apps.yml -f docker-compose.apps.e2e.yml)

case "${1:-up}" in
  up)
    "${INFRA[@]}" up -d
    # Dockerfile은 이미 생성된 bootJar를 COPY하므로 Compose의 --build만으로는
    # 소스 변경이 이미지에 들어가지 않는다. 한 진입점에서 항상 JAR도 갱신한다.
    ./gradlew bootJar -x test -x integrationTest --no-daemon
    "${APPS[@]}" up -d --build
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
