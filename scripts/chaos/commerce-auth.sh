#!/usr/bin/env bash

# 장애 실험의 모든 구매는 하나의 테스트 회원 토큰으로 수행한다.
require_member_token() {
    : "${MEMBER_TOKEN:?MEMBER_TOKEN OAuth2 access token이 필요합니다}"
}

member_id_from_token() {
    require_member_token
    local payload=${MEMBER_TOKEN#*.}
    payload=${payload%%.*}
    while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="${payload}="; done
    printf '%s' "$payload" | tr '_-' '/+' | base64 -d 2>/dev/null | jq -er '.member_id'
}

pg_signature() {
    local timestamp=$1 body=$2
    : "${PG_CALLBACK_SECRET:?PG_CALLBACK_SECRET이 필요합니다}"
    printf '%s.%s' "$timestamp" "$body" \
        | openssl dgst -sha256 -hmac "$PG_CALLBACK_SECRET" \
        | awk '{print $NF}'
}
