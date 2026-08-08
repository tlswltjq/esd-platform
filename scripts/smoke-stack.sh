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
# await 조건으로 쓰는 판정기들. 헤더 같은 추가 curl 인자를 URL 뒤에 붙일 수 있다.
has()     { local url=$1 pat=$2; shift 2
            "${RUNNER[@]}" "$@" "$url" | grep -q "$pat"; }
is_code() { local want=$1 url=$2; shift 2
            [ "$("${RUNNER[@]}" -o /dev/null -w '%{http_code}' "$@" "$url")" = "$want" ]; }
# 부재 판정. 응답 자체가 실패한 것을 '없어졌다'로 착각하지 않도록 봉투를 먼저 본다.
lacks()   { local url=$1 pat=$2; shift 2
            local out; out=$("${RUNNER[@]}" "$@" "$url") || return 1
            printf '%s' "$out" | grep -q '"success":true' || return 1
            ! printf '%s' "$out" | grep -q "$pat"; }

echo
echo "=== 1. 트랙 A — 등록 → 심의 → 노출 ==="
TS=$(date +%s)
CODE="GAME-SMOKE-$TS"
SELLER=1001               # settlement 의 self-seller-id(1) 가 아니므로 입점(PARTNER) 판매다
PRICE=18000
FEE=$((PRICE * 30 / 100)) # partner-fee-rate 기본값 0.3000
NET=$((PRICE - FEE))
MEMBER=$((TS % 1000000))  # 실행마다 다른 구매자 — 스택은 재사용되고 볼륨도 남는다
OTHER=$((MEMBER + 1))     # 미보유 회원

req "studio: 프로젝트 생성" 200 -X POST http://studio:8085/api/v1/studio/games \
    -H 'Content-Type: application/json' \
    -d "{\"productCode\":\"$CODE\",\"title\":\"스모크 테스트 게임\",\"sellerId\":$SELLER,\"price\":$PRICE,\"selfRated\":true}"
GAME_ID=$(printf '%s' "$BODY" | grep -o '"gameId":[0-9]*' | head -1 | cut -d: -f2)
GAME_ID=${GAME_ID:-1}
echo "    gameId=$GAME_ID productCode=$CODE"

req "studio: 심의 신청 → GameRegistered" 200 \
    -X POST "http://studio:8085/api/v1/studio/games/$GAME_ID/submit" -H "X-Seller-Id: $SELLER"

# 자체등급분류는 review 가 자동 승인한다 → ReviewApproved
await "review: 자동 승인 (GameRegistered 수신)" 60 \
    bash -c "\"\$@\" http://review:8086/api/v1/reviews | grep -q '\"productCode\":\"$CODE\".*\"status\":\"APPROVED\"'" _ "${RUNNER[@]}"

# review → studio 역전파
await "studio: 상태 역전파 APPROVED" 60 \
    bash -c "\"\$@\" -H 'X-Seller-Id: $SELLER' http://studio:8085/api/v1/studio/games | grep -q '\"productCode\":\"$CODE\".*\"status\":\"APPROVED\"'" _ "${RUNNER[@]}"

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

# 빌드 등록은 심의와 독립이다. 트랙 C 의 다운로드 티켓이 이 매니페스트를 요구하므로
# (issueTicket 은 ProductRef + PatchManifest 를 둘 다 찾는다) 여기서 올려 둔다.
req "studio: 빌드 등록 → BuildUploaded" 200 \
    -X POST "http://studio:8085/api/v1/studio/games/$GAME_ID/builds" \
    -H "X-Seller-Id: $SELLER" -H 'Content-Type: application/json' \
    -d '{"version":"1.0.0","fileSize":1073741824,"checksum":"a1b2c3"}'

await "download: 패치 매니페스트 등록 (BuildUploaded 관통)" 60 \
    has "http://download:8088/api/v1/downloads/$CODE/manifests" '"version":"1.0.0"'

echo
echo "=== 2. 트랙 B — 주문 → 결제 ==="
ORDER_NO=""
if [ -n "$PRODUCT_ID" ]; then
    # 게이트 1 — 클라이언트 금액을 신뢰하지 않는다. catalog 재계산과 다르면 주문이 만들어지지 않는다.
    req "order: 금액 위조 주문 거부 (PRICE_MISMATCH)" 409 \
        -X POST http://order:8082/api/v1/orders -H 'Content-Type: application/json' \
        -d "{\"memberId\":$MEMBER,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}],\"expectedAmount\":100}"

    req "order: 주문 생성 → OrderCreated" 200 \
        -X POST http://order:8082/api/v1/orders -H 'Content-Type: application/json' \
        -d "{\"memberId\":$MEMBER,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}],\"expectedAmount\":$PRICE}"
    ORDER_NO=$(printf '%s' "$BODY" | grep -o '"orderNo":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "    orderNo=$ORDER_NO memberId=$MEMBER"
fi

if [ -n "$ORDER_NO" ]; then
    await "payment: 결제 대기 생성 (OrderCreated 관통)" 60 \
        has "http://payment:8083/api/v1/payments/$ORDER_NO" '"status":"READY"'

    # 게이트 2 — 승인 전에 서버가 확정한 금액을 PG 에 먼저 등록한다
    req "payment: PG 사전등록 → PENDING" 200 \
        -X POST "http://payment:8083/api/v1/payments/$ORDER_NO/prepare" \
        -H 'Content-Type: application/json' -d '{"method":"STOVE_CASH"}'

    # 게이트 3 — 사전등록 금액과 다른 승인은 확정하지 않는다.
    # prepare 뒤에 보내야 '승인 불가 상태'가 아니라 금액 대조에서 걸린다.
    # 콜백은 result 로 승인/거절이 갈린다. 기본값이 없으므로 빠뜨리면 400 이다 —
    # "표현되지 않은 결과가 조용히 승인이 되는" 성질을 만들지 않으려는 의도적 선택이다.
    req "payment: result 없는 콜백 거부 (400)" 400 \
        -X POST http://payment:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
        -d "{\"orderNo\":\"$ORDER_NO\",\"pgTxId\":\"PG-SMOKE-$TS\",\"paidAmount\":$PRICE,\"idempotencyKey\":\"IDEM-X-$TS\"}"

    req "payment: 금액 불일치 콜백 거부 (PAYMENT_AMOUNT_MISMATCH)" 409 \
        -X POST http://payment:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
        -d "{\"result\":\"APPROVED\",\"orderNo\":\"$ORDER_NO\",\"pgTxId\":\"PG-SMOKE-$TS\",\"paidAmount\":1,\"idempotencyKey\":\"IDEM-BAD-$TS\"}"

    req "payment: 승인 콜백 → PaymentCompleted" 200 \
        -X POST http://payment:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
        -d "{\"result\":\"APPROVED\",\"orderNo\":\"$ORDER_NO\",\"pgTxId\":\"PG-SMOKE-$TS\",\"paidAmount\":$PRICE,\"idempotencyKey\":\"IDEM-$TS\"}"

    has "http://payment:8083/api/v1/payments/$ORDER_NO" '"status":"PAID"' \
        && ok "payment: PAID 확정" \
        || bad "payment: PAID 확정" "거부된 콜백 뒤에도 PENDING 이어야 하고 정상 콜백으로 PAID 여야 한다"
fi

echo
echo "=== 3. 트랙 C — 지급 → 다운로드 → 정산 → 환불 ==="
if [ -n "$ORDER_NO" ]; then
    await "license: 라이선스 지급 (PaymentCompleted 관통)" 60 \
        has http://license:8084/api/v1/library "\"orderNo\":\"$ORDER_NO\"" -H "X-Member-Id: $MEMBER"

    await "order: 주문 확정 PAID (PaymentCompleted 관통)" 60 \
        has "http://order:8082/api/v1/orders/$ORDER_NO" '"status":"PAID"' -H "X-Member-Id: $MEMBER"

    # download 는 license 를 동기 호출하지 않는다 — 이벤트로 받아둔 권한 사본으로 판정한다
    await "download: 다운로드 권한 부여 (LicenseIssued 관통)" 60 \
        is_code 200 "http://download:8088/api/v1/downloads/$CODE/ticket" -H "X-Member-Id: $MEMBER"

    req "download: 티켓 발급" 200 -H "X-Member-Id: $MEMBER" \
        "http://download:8088/api/v1/downloads/$CODE/ticket"
    printf '%s' "$BODY" | grep -q '"downloadUrl"' \
        && ok "download: 서명 URL 포함" \
        || bad "download: 서명 URL 포함" "downloadUrl 이 없다: ${BODY:0:120}"

    req "download: 미보유 회원은 403" 403 -H "X-Member-Id: $OTHER" \
        "http://download:8088/api/v1/downloads/$CODE/ticket"

    await "settlement: 매출 원장 적립 (PaymentCompleted 관통)" 60 \
        has "http://settlement:8089/api/v1/settlements/orders/$ORDER_NO" '"recordType":"SALE"'

    req "settlement: 주문 원장 조회" 200 \
        "http://settlement:8089/api/v1/settlements/orders/$ORDER_NO"
    if printf '%s' "$BODY" | grep -q "\"saleType\":\"PARTNER\".*\"grossAmount\":$PRICE.*\"netAmount\":$NET,"; then
        ok "settlement: 입점 수수료 30% (gross=$PRICE fee=$FEE net=$NET)"
    else
        bad "settlement: 입점 수수료 30%" "gross=$PRICE net=$NET 를 찾지 못했다: ${BODY:0:200}"
    fi

    # 멱등 — 같은 콜백이 다시 와도 이벤트를 재발행하지 않으므로 라이선스가 늘지 않는다.
    # '아무 일도 일어나지 않음'은 await 로 기다릴 수 없다. 재발행이 있었다면 도착했을
    # 시간만큼만 주고 센다 — 이 스크립트에서 고정 대기가 옳은 유일한 자리다.
    # 릴레이 폴링이 1초(stove.outbox.poll-interval-ms)라 6초면 6주기다.
    before=$("${RUNNER[@]}" -H "X-Member-Id: $MEMBER" http://license:8084/api/v1/library \
        | grep -o '"licenseId"' | wc -l | tr -d ' ')
    req "payment: 중복 콜백 흡수" 200 \
        -X POST http://payment:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
        -d "{\"result\":\"APPROVED\",\"orderNo\":\"$ORDER_NO\",\"pgTxId\":\"PG-SMOKE-$TS\",\"paidAmount\":$PRICE,\"idempotencyKey\":\"IDEM-$TS\"}"
    sleep 6
    after=$("${RUNNER[@]}" -H "X-Member-Id: $MEMBER" http://license:8084/api/v1/library \
        | grep -o '"licenseId"' | wc -l | tr -d ' ')
    [ "$before" = "$after" ] \
        && ok "license: 중복 콜백에도 지급은 1회 (${after}건 유지)" \
        || bad "license: 중복 지급" "$before건 → $after건"

    # ── 환불: 하나의 이벤트가 세 방향으로 되감긴다 ──────────────────────
    req "payment: 환불 → PaymentCancelled" 200 \
        -X POST "http://payment:8083/api/v1/payments/$ORDER_NO/cancel?reason=SMOKE_REFUND"

    await "order: 주문 취소 (PaymentCancelled 관통)" 60 \
        has "http://order:8082/api/v1/orders/$ORDER_NO" '"status":"CANCELED"' -H "X-Member-Id: $MEMBER"

    # 라이브러리는 ACTIVE 만 반환한다 — 회수되면 목록에서 사라지는 것이 정상이다
    await "license: 라이선스 회수 (라이브러리에서 사라진다)" 60 \
        lacks http://license:8084/api/v1/library "\"orderNo\":\"$ORDER_NO\"" -H "X-Member-Id: $MEMBER"

    await "download: 권한 회수 → 403 (LicenseRevoked 관통)" 60 \
        is_code 403 "http://download:8088/api/v1/downloads/$CODE/ticket" -H "X-Member-Id: $MEMBER"

    # 환불 이벤트에는 항목 정보가 없다. settlement 는 자기 원장의 SALE 을 부호 반전해 상계한다.
    await "settlement: 환불 역산 (부호 반전)" 60 \
        has "http://settlement:8089/api/v1/settlements/orders/$ORDER_NO" \
            "\"recordType\":\"REFUND\".*\"grossAmount\":-$PRICE"

    req "settlement: 상계 후 원장 조회" 200 \
        "http://settlement:8089/api/v1/settlements/orders/$ORDER_NO"
    if printf '%s' "$BODY" | grep -q "\"netAmount\":$NET," \
       && printf '%s' "$BODY" | grep -q "\"netAmount\":-$NET,"; then
        ok "settlement: SALE(+$NET) + REFUND(-$NET) 순액 0"
    else
        bad "settlement: 상계" "+$NET 와 -$NET 가 함께 있어야 한다: ${BODY:0:200}"
    fi
fi

echo
echo "=== 3-B. 결제 실패 경로 (PG 승인 거절) ==="
# 승인과 대칭인 경로다. 승인 경로를 탄 주문은 재사용할 수 없으므로 주문을 새로 만든다 —
# FAILED 는 종단 상태라 카드를 바꿔 재시도하려면 어차피 새 주문이어야 한다.
if [ -n "$PRODUCT_ID" ]; then
    req "order: 실패 검증용 주문 생성" 200 \
        -X POST http://order:8082/api/v1/orders -H 'Content-Type: application/json' \
        -d "{\"memberId\":$MEMBER,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}],\"expectedAmount\":$PRICE}"
    FAIL_ORDER_NO=$(printf '%s' "$BODY" | grep -o '"orderNo":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [ -n "$FAIL_ORDER_NO" ]; then
        await "payment: 결제 대기 생성" 60 \
            has "http://payment:8083/api/v1/payments/$FAIL_ORDER_NO" '"status":"READY"'

        # 거절은 멱등키가 없다(돈이 움직이지 않아 PG 가 만들 승인 거래 키가 없다).
        # 그래서 pgTxId 가 이 거절이 어느 거래의 것인지 가리키는 유일한 값이고,
        # 사전등록이 돌려준 값을 그대로 써야 한다.
        req "payment: PG 사전등록 → PENDING" 200 \
            -X POST "http://payment:8083/api/v1/payments/$FAIL_ORDER_NO/prepare" \
            -H 'Content-Type: application/json' -d '{"method":"CARD"}'
        PG_TX=$(printf '%s' "$BODY" | grep -o '"pgTxId":"[^"]*"' | head -1 | cut -d'"' -f4)

        req "payment: 다른 거래의 거절 콜백 거부 (PAYMENT_TX_MISMATCH)" 409 \
            -X POST http://payment:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
            -d "{\"result\":\"DECLINED\",\"orderNo\":\"$FAIL_ORDER_NO\",\"pgTxId\":\"PG-NOPE-$TS\",\"reasonCode\":\"REJECT_CARD_COMPANY\",\"reason\":\"카드사 거절\"}"

        req "payment: 거절 콜백 → PaymentFailed" 200 \
            -X POST http://payment:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
            -d "{\"result\":\"DECLINED\",\"orderNo\":\"$FAIL_ORDER_NO\",\"pgTxId\":\"$PG_TX\",\"reasonCode\":\"REJECT_CARD_COMPANY\",\"reason\":\"카드사 거절\"}"

        has "http://payment:8083/api/v1/payments/$FAIL_ORDER_NO" '"status":"FAILED"' \
            && ok "payment: FAILED 확정" \
            || bad "payment: FAILED 확정" "거절 콜백 뒤에는 FAILED 여야 한다"

        # 이게 이 절의 핵심이다 — 예전에는 여기서 주문이 CREATED 에 영구히 머물렀다.
        await "order: 주문 실패 종료 FAILED (PaymentFailed 관통)" 60 \
            has "http://order:8082/api/v1/orders/$FAIL_ORDER_NO" '"status":"FAILED"' -H "X-Member-Id: $MEMBER"

        # 중복 거절은 종단 상태로 흡수한다(승인이 멱등키로 흡수하는 것과 같은 자리).
        req "payment: 중복 거절 콜백 흡수" 200 \
            -X POST http://payment:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
            -d "{\"result\":\"DECLINED\",\"orderNo\":\"$FAIL_ORDER_NO\",\"pgTxId\":\"$PG_TX\",\"reasonCode\":\"REJECT_CARD_COMPANY\",\"reason\":\"카드사 거절\"}"

        # 거절 뒤에 오는 승인은 엇갈린 콜백이다. 조용히 삼키면 사고가 관측되지 않는다.
        req "payment: 거절 뒤 승인 콜백 거부 (409)" 409 \
            -X POST http://payment:8083/api/v1/payments/callback -H 'Content-Type: application/json' \
            -d "{\"result\":\"APPROVED\",\"orderNo\":\"$FAIL_ORDER_NO\",\"pgTxId\":\"$PG_TX\",\"paidAmount\":$PRICE,\"idempotencyKey\":\"IDEM-LATE-$TS\"}"
    fi
fi

echo
echo "=== 4. 인프라 도달성 ==="
req "elasticsearch" 200 http://elasticsearch:9200/_cluster/health

echo
echo "=== 5. 게이트웨이 내부 API 차단 ==="
req "quote 는 게이트웨이로 못 부른다" 404 -X POST http://gateway:8080/api/v1/products/quote \
    -H 'Content-Type: application/json' -d '{"items":[]}'

echo
echo "=== 6. 액추에이터 (앱 10종) ==="
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
