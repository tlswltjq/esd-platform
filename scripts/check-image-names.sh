#!/usr/bin/env bash
# 결정 135: Compose가 푼 최종 이미지 이름(.env·환경 변수 포함)이 Docker Hub의 stove 계정을 가리키면
# 시작하지 않는다. 그 계정은 이 프로젝트와 무관한 다른 사람 것이라, 로컬에 없으면 남의 이미지를 받는다.
# 사용법: bash scripts/check-image-names.sh docker compose -p stove-apps -f docker-compose.apps.yml
# 같은 규칙: tools/verification/support/image_names.py(테스트가 이 함수와 비교한다), 루트 build.gradle.
set -euo pipefail

# 이미지 이름이 Docker Hub의 stove 계정으로 해석되면 0을 돌려준다.
docker_hub_stove() {
    local name=${1%%@*} head="" last first host
    if [[ "$name" == */* ]]; then
        head=${name%/*}/
    fi
    last=${name##*/}
    name=$head${last%%:*}
    first=${name%%/*}
    if [[ "$name" == */* && ( "$first" == *.* || "$first" == *:* || "$first" == localhost ) ]]; then
        host=$(printf '%s' "$first" | tr '[:upper:]' '[:lower:]')
        case "$host" in
            docker.io|index.docker.io|registry-1.docker.io) name=${name#*/} ;;
            *) return 1 ;;
        esac
    fi
    [[ "$name" == stove/* ]]
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    if [ "$#" -eq 0 ]; then
        echo "사용법: $0 docker compose [-p 이름] -f 파일 ..." >&2
        exit 2
    fi
    images=$("$@" config --images)
    refused=()
    while IFS= read -r image; do
        if [ -n "$image" ] && docker_hub_stove "$image"; then
            refused+=("$image")
        fi
    done <<< "$images"
    if [ "${#refused[@]}" -gt 0 ]; then
        echo "Docker Hub의 다른 사람 계정(stove)을 가리키는 이미지가 있어 시작하지 않는다(결정 135): ${refused[*]}" >&2
        echo "STOVE_JIB_REGISTRY·STOVE_MINIO_IMAGE(.env 포함)를 지우거나 localhost/stove 또는 우리 registry로 바꾼다." >&2
        exit 1
    fi
fi
