#!/usr/bin/env bash
set -euo pipefail

# 인프라가 준비된 뒤 생성된 이미지를 의존 순서로 실행한다.
# 사용법: bash scripts/start-apps.sh -p stove-apps -f docker-compose.apps.yml [...]
compose=(docker compose "$@")
app_wait_timeout="${STOVE_APP_WAIT_TIMEOUT:-240}"
# 결정 118: Docker Hub의 다른 사람 계정(stove)에서 이미지를 받지 않도록 Compose가 푼 최종 이름을 먼저 확인한다.
bash "$(dirname "$0")/check-image-names.sh" "${compose[@]}"
"${compose[@]}" up -d --no-build --wait --wait-timeout "$app_wait_timeout" mock-pg auth gateway
"${compose[@]}" up -d --no-build --wait --wait-timeout "$app_wait_timeout" studio review catalog
"${compose[@]}" up -d --no-build --wait --wait-timeout "$app_wait_timeout" store order payment
"${compose[@]}" up -d --no-build --wait --wait-timeout "$app_wait_timeout" license download settlement
