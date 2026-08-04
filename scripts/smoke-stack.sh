#!/usr/bin/env bash
#
# 전체 스택(인프라 9 + 앱 10)이 실제로 관통하는지 확인한다.
#
#   ./scripts/smoke-stack.sh
#
# README 5장의 curl 시나리오와 같은 흐름이되, **호스트 포트를 쓰지 않는다.**
# CI 호스트는 공개 IP 를 가지므로 포트를 열지 않고(docker-compose.ci.yml),
# 대신 같은 네트워크에 컨테이너를 띄워 서비스 이름으로 부른다.
#
# 사전 조건:
#   docker compose -f docker-compose.yml      -f docker-compose.ci.yml      up -d
#   docker compose -f docker-compose.apps.yml -f docker-compose.apps.ci.yml up -d
set -uo pipefail

NET=stove_default
RUNNER=(docker run --rm --network "$NET" curlimages/curl:latest -s)

pass=0; fail=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31m✗\033[0m %s — %s\n' "$1" "$2"; fail=$((fail+1)); }

# req <이름> <기대 상태코드> <curl 인자...>
req() {
    local name=$1 want=$2; shift 2
    local out code
    out=$("${RUNNER[@]}" -w '\n%{http_code}' "$@" 2>&1)
    code=$(printf '%s' "$out" | tail -1)
    BODY=$(printf '%s' "$out" | sed '$d')
    if [ "$code" = "$want" ]; then ok "$name ($code)"; else bad "$name" "기대 $want, 실제 $code: ${BODY:0:120}"; fi
}

echo "=== 0. 컨테이너 상태 ==="
total=$(docker ps --format '{{.Names}}' | wc -l | tr -d ' ')
unhealthy=$(docker ps --filter health=unhealthy --format '{{.Names}}' | tr '\n' ' ')
echo "  실행중 $total 개"
[ -n "$unhealthy" ] && bad "healthcheck" "unhealthy: $unhealthy" || ok "unhealthy 없음"

# 이벤트 전파는 Outbox 폴링 릴레이를 거친다 — 고정 sleep 이 아니라 조건을 기다린다.
# await <이름> <제한초> <조건 명령…>
await() {
    local name=$1 limit=$2; shift 2
    local waited=0
    while [ "$waited" -lt "$limit" ]; do
        if "$@" >/dev/null 2>&1; then ok "$name (${waited}s)"; return 0; fi
        sleep 2; waited=$((waited+2))
    done
    bad "$name" "${limit}s 안에 조건 불성립"; return 1
}
has() { "${RUNNER[@]}" "$1" | grep -q "$2"; }

echo
echo "=== 1. 트랙 A — 등록 → 심의 → 노출 ==="
CODE="GAME-SMOKE-$(date +%s)"
req "studio: 프로젝트 생성" 200 -X POST http://studio:8085/api/v1/studio/games \
    -H 'Content-Type: application/json' \
    -d "{\"productCode\":\"$CODE\",\"title\":\"스모크 테스트 게임\",\"sellerId\":1001,\"price\":18000,\"selfRated\":true}"
GAME_ID=$(printf '%s' "$BODY" | grep -o '"gameId":[0-9]*' | head -1 | cut -d: -f2)
GAME_ID=${GAME_ID:-1}
echo "    gameId=$GAME_ID productCode=$CODE"

req "studio: 심의 신청 → GameRegistered" 200 \
    -X POST "http://studio:8085/api/v1/studio/games/$GAME_ID/submit" -H 'X-Seller-Id: 1001'

# 자체등급분류는 review 가 자동 승인한다 → ReviewApproved
await "review: 자동 승인 (GameRegistered 수신)" 60 \
    bash -c "\"\$@\" http://review:8086/api/v1/reviews | grep -q '\"productCode\":\"$CODE\".*\"status\":\"APPROVED\"'" _ "${RUNNER[@]}"

# review → studio 역전파
await "studio: 상태 역전파 APPROVED" 60 \
    bash -c "\"\$@\" -H 'X-Seller-Id: 1001' http://studio:8085/api/v1/studio/games | grep -q '\"productCode\":\"$CODE\".*\"status\":\"APPROVED\"'" _ "${RUNNER[@]}"

# catalog 는 ReviewApproved 로 상품 마스터를 만든다. 다만 목록(GET /products)은
# getOnSaleProducts() 라 판매 시작 전에는 뜨지 않는다 — 상세로 찾는다.
PRODUCT_ID=""
for _ in $(seq 1 30); do
    for id in $(seq 1 12); do
        if "${RUNNER[@]}" "http://catalog:8081/api/v1/products/$id" 2>/dev/null | grep -q "\"productCode\":\"$CODE\""; then
            PRODUCT_ID=$id; break 2
        fi
    done
    sleep 2
done
if [ -n "$PRODUCT_ID" ]; then
    ok "catalog: 상품 마스터 생성 (ReviewApproved 수신, productId=$PRODUCT_ID)"
else
    bad "catalog: 상품 마스터 생성" "$CODE 를 상세 조회로도 못 찾았다"
fi

if [ -n "$PRODUCT_ID" ]; then
    req "catalog: 판매 시작 → ProductChanged" 200 \
        -X POST "http://catalog:8081/api/v1/products/$PRODUCT_ID/sale-open"
    await "catalog: ON_SALE 목록 노출" 30 \
        bash -c "\"\$@\" http://catalog:8081/api/v1/products | grep -q '$CODE'" _ "${RUNNER[@]}"
    await "store: 검색 색인 반영 (ProductChanged 관통)" 60 \
        bash -c "\"\$@\" -G http://store:8087/api/v1/storefront/products --data-urlencode 'q=스모크' | grep -q '$CODE'" _ "${RUNNER[@]}"
fi

echo
echo "=== 2. 인프라 도달성 ==="
req "elasticsearch" 200 http://elasticsearch:9200/_cluster/health

echo
echo "=== 3. 게이트웨이 내부 API 차단 ==="
req "quote 는 게이트웨이로 못 부른다" 404 -X POST http://gateway:8080/api/v1/products/quote \
    -H 'Content-Type: application/json' -d '{"items":[]}'

echo
echo "=== 4. 액추에이터 (앱 10종) ==="
for svc in gateway:8080 catalog:8081 order:8082 payment:8083 license:8084 \
           studio:8085 review:8086 store:8087 download:8088 settlement:8089; do
    name=${svc%%:*}
    st=$("${RUNNER[@]}" -o /dev/null -w '%{http_code}' "http://$svc/actuator/health" 2>/dev/null)
    [ "$st" = "200" ] && ok "$name health" || bad "$name health" "HTTP $st"
done

echo
echo "════════════════════════════════"
printf '  통과 %d / 실패 %d\n' "$pass" "$fail"
echo "════════════════════════════════"
[ "$fail" -eq 0 ]
