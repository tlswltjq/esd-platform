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

echo "    이벤트 전파 대기 (studio → review → catalog)…"
sleep 8

req "review: 심의 목록 조회" 200 http://review:8086/api/v1/reviews
req "catalog: 상품 목록 조회"  200 http://catalog:8081/api/v1/products
if printf '%s' "$BODY" | grep -q "$CODE"; then
    ok "catalog 에 $CODE 반영 (ReviewApproved → ProductChanged 관통)"
else
    bad "catalog 반영" "$CODE 가 상품 목록에 없다"
fi

echo
echo "=== 2. 인프라 도달성 ==="
req "elasticsearch" 200 http://elasticsearch:9200/_cluster/health
req "store 검색"     200 -G http://store:8087/api/v1/storefront/products --data-urlencode 'q=스모크'

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
