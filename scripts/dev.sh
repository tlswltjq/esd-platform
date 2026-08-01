#!/usr/bin/env bash
#
# Docker 하나만 있는 머신에서 빌드·테스트를 돌리기 위한 진입점.
#
#   ./scripts/dev.sh                  대화형 셸
#   ./scripts/dev.sh ./gradlew build  명령 실행 후 종료
#
# JDK 도 mise 도 direnv 도 node 도 필요 없다. devcontainer 와 같은 베이스 이미지를 쓰되,
# 안쪽에 Docker 데몬을 또 띄우지 않고 호스트 소켓을 빌린다 — 호스트 이미지 캐시를 그대로
# 쓰므로 처음 실행이 훨씬 빠르다. 대신 호스트가 Docker Desktop 이나 OrbStack 이라고 가정한다.
# 그 가정을 하고 싶지 않으면 .devcontainer 쪽(docker-in-docker)을 쓴다.
#
# 애플리케이션 스택 실행(docker compose)은 호스트에서 한다. 이 셸은 빌드와 테스트용이고,
# Testcontainers 는 호스트 데몬에 형제 컨테이너를 띄운다.
set -euo pipefail

IMAGE="mcr.microsoft.com/devcontainers/java:21-bookworm"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# OrbStack 은 CLI 를 앱 번들 안에 둔다. Docker Desktop 이면 이미 PATH 에 있다.
if ! command -v docker >/dev/null 2>&1 && [ -d /Applications/OrbStack.app/Contents/MacOS/xbin ]; then
    PATH="/Applications/OrbStack.app/Contents/MacOS/xbin:$PATH"
fi
command -v docker >/dev/null 2>&1 || {
    echo "docker 를 찾을 수 없습니다. Docker Desktop 또는 OrbStack 을 실행하세요." >&2
    exit 1
}

# 활성 컨텍스트에서 소켓 경로를 뽑는다. macOS + OrbStack 은 /var/run/docker.sock 이 없다.
ENDPOINT="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
SOCKET="${ENDPOINT#unix://}"
[ -S "$SOCKET" ] || {
    echo "docker 소켓을 찾을 수 없습니다: ${ENDPOINT:-<없음>}" >&2
    exit 1
}

if [ "$#" -eq 0 ]; then
    INNER="exec su vscode"
    TTY_FLAGS=(-it)
else
    INNER="exec su vscode -c $(printf '%q' "$*")"
    TTY_FLAGS=(-i)
fi

exec docker run --rm "${TTY_FLAGS[@]}" \
    -v "$REPO:/workspaces/stove" \
    -v "$SOCKET:/var/run/docker.sock" \
    -v stove-gradle-cache:/home/vscode/.gradle \
    -w /workspaces/stove \
    --user root \
    -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    "$IMAGE" \
    bash -lc "chown vscode /var/run/docker.sock 2>/dev/null || true
              chown -R vscode /home/vscode/.gradle 2>/dev/null || true
              $INNER"
