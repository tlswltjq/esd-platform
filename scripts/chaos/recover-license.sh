#!/usr/bin/env bash
#
# license 원장 유실 복구 — 런북(docs/runbooks/license-db-loss.md)을 그대로 실행하고 대사한다.
#
#   scripts/chaos/recover-license.sh --seed 200            # 유실을 만들고 복구까지
#   scripts/chaos/recover-license.sh --seed 200 --skip-inbox-purge   # 2번 단계를 빼고 돌린다
#
# ── 왜 '빼고 돌리는' 옵션이 있는가 ──────────────────────────────────
#
# 절차서의 각 줄은 "왜 필요한가"가 함께 적혀 있어야 지켜진다. 이유가 없으면 바쁠 때 생략된다.
# 2번(가드 행 삭제)은 특히 그렇다 — 빼고 돌려도 **아무 데서도 실패하지 않기 때문이다.**
# 예외도 없고, 실패 지표도 안 오르고, 로그는 info 다. 0건 복구가 성공과 구분되지 않는다.
#
# 그래서 이 스크립트는 그 단계를 **뺄 수 있게** 만들었다. 한 번 빼고 돌려 보면
# 절차서의 그 줄이 무엇을 막고 있는지가 숫자로 남는다.
set -uo pipefail

ORDER_URL=${ORDER_URL:-http://localhost:8082}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8083}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-stove-mysql}
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-root1234}
KAFKA_CONTAINER=${KAFKA_CONTAINER:-stove-kafka}
KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP:-localhost:19092}
LICENSE_CONTAINER=${LICENSE_CONTAINER:-stove-apps-license-1}
PRODUCT_ID=${PRODUCT_ID:-1}

SEED=0
SKIP_PURGE=no

while [ $# -gt 0 ]; do
    case "$1" in
        --seed) SEED=$2; shift 2 ;;
        --skip-inbox-purge) SKIP_PURGE=yes; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "모르는 옵션: $1" >&2; exit 1 ;;
    esac
done

sql() { docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "$1" 2>/dev/null; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

paid_count()    { sql "select count(*) from stove_payment.payment where status='PAID';"; }
license_count() { sql "select count(distinct order_no) from stove_license.license;"; }

# ── 유실 상황 만들기 (선택) ─────────────────────────────────────────

if [ "$SEED" -gt 0 ]; then
    log "── 시드 $SEED 건 구매 ──"
    for i in $(seq 1 "$SEED"); do
        (
            member=$((900000 + i))
            order=$(curl -s -m 10 -X POST "$ORDER_URL/api/v1/orders" -H 'Content-Type: application/json' \
                -d "{\"memberId\":$member,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}]}")
            no=$(echo "$order" | sed -n 's/.*"orderNo":"\([^"]*\)".*/\1/p')
            amt=$(echo "$order" | sed -n 's/.*"totalAmount":\([0-9]*\).*/\1/p')
            [ -n "$no" ] || exit 0
            deadline=$((SECONDS + 30))
            while [ $SECONDS -lt $deadline ]; do
                curl -s -m 5 "$PAYMENT_URL/api/v1/payments/$no" | grep -q '"status":"READY"' && break
                sleep 0.3
            done
            curl -s -m 10 -X POST "$PAYMENT_URL/api/v1/payments/$no/prepare" \
                -H 'Content-Type: application/json' -d '{"method":"CARD"}' >/dev/null
            curl -s -m 10 -X POST "$PAYMENT_URL/api/v1/payments/callback" -H 'Content-Type: application/json' \
                -d "{\"result\":\"APPROVED\",\"orderNo\":\"$no\",\"pgTxId\":\"PG-REC-$no\",\"paidAmount\":$amt,\"idempotencyKey\":\"IDEM-REC-$no\"}" >/dev/null
        ) &
        [ $((i % 10)) -eq 0 ] && wait
    done
    wait
    log "  지급이 따라잡기를 기다린다"
    for _ in $(seq 1 60); do
        [ "$(paid_count)" = "$(license_count)" ] && break
        sleep 1
    done
fi

# ── 0. 멈춘다 ───────────────────────────────────────────────────────

log "── 0. license 정지 (복구 범위를 고정한다) ──"
docker stop "$LICENSE_CONTAINER" >/dev/null

# ── 1. 대사 기준 ────────────────────────────────────────────────────

PAID=$(paid_count)
BEFORE=$(license_count)
log "── 1. 대사 기준 — 결제완료 $PAID · 라이선스 $BEFORE ──"

# 유실을 만든다. 원장만 지운다 — 가드는 그대로 두는 것이 이 결함의 조건이다.
log "  원장을 지운다 (가드는 남긴다)"
sql "delete from stove_license.license;"
GUARDS=$(sql "select count(*) from stove_license.processed_event where consumer_group='license';")
log "  원장 $(license_count) · 살아남은 가드 $GUARDS"

# ── 2. 가드 행 ──────────────────────────────────────────────────────

if [ "$SKIP_PURGE" = yes ]; then
    log "── 2. 가드 행 삭제 — **건너뛴다** (절차서의 그 줄이 없을 때를 잰다) ──"
else
    log "── 2. 가드 행 삭제 ──"
    # event_type 을 반드시 건다. 회수(PaymentCancelled) 가드까지 지우면 되읽기가 회수를 다시 실행한다.
    sql "delete from stove_license.processed_event
         where consumer_group='license' and event_type='PaymentCompleted';"
    log "  남은 가드 $(sql "select count(*) from stove_license.processed_event where consumer_group='license';")"
fi

# ── 3. 오프셋 리셋 ──────────────────────────────────────────────────

log "── 3. 오프셋을 맨 앞으로 ──"
docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server "$KAFKA_BOOTSTRAP" --group license \
    --topic stove.payment.v1 --reset-offsets --to-earliest --execute >/dev/null 2>&1 \
    || { log "  ✗ 리셋 실패 — 그룹이 아직 활성이거나 없는 그룹이다. 회차를 버린다"; exit 2; }

# ── 4. 켜고 대사 ────────────────────────────────────────────────────

log "── 4. license 기동 후 대사 ──"
docker start "$LICENSE_CONTAINER" >/dev/null
START=$(date +%s)

# 기동 자체를 기다린다. 이 시간은 복구 속도에 넣지 않는다.
for _ in $(seq 1 60); do
    curl -s -o /dev/null -m 3 "http://localhost:8084/actuator/health" && break
    sleep 1
done
READY_AT=$(date +%s)

LAST=-1 STABLE=0
for _ in $(seq 1 180); do
    NOW=$(license_count)
    if [ "$NOW" = "$LAST" ]; then STABLE=$((STABLE + 1)); else STABLE=0; fi
    LAST=$NOW
    # 목표에 닿았거나, 10초 동안 늘지 않으면 끝난 것으로 본다.
    [ "$NOW" -ge "$PAID" ] && break
    [ $STABLE -ge 10 ] && break
    sleep 1
done
END=$(date +%s)

AFTER=$(license_count)
ELAPSED=$((END - READY_AT))
RECOVERED=$((AFTER - 0))

echo
echo "════════ 복구 판정 ════════"
printf "%-22s %s\n" "2번(가드 삭제) 단계" "$([ "$SKIP_PURGE" = yes ] && echo '건너뜀' || echo '수행')"
printf "%-22s %s\n" "결제완료(있어야 할 수)" "$PAID"
printf "%-22s %s\n" "복구된 주문" "$RECOVERED"
printf "%-22s %s\n" "대사" "$PAID : $AFTER $([ "$PAID" = "$AFTER" ] && echo '  일치' || echo '  ✗ 불일치')"
printf "%-22s %s\n" "되읽기~대사 일치" "${ELAPSED}s"
if [ "$RECOVERED" -gt 0 ] && [ "$ELAPSED" -gt 0 ]; then
    printf "%-22s %s\n" "복구 속도" "$(awk -v r="$RECOVERED" -v e="$ELAPSED" 'BEGIN{printf "%.1f 건/s", r/e}')"
fi
printf "%-22s %s\n" "컨슈머 예외" "$(docker logs "$LICENSE_CONTAINER" --since "${ELAPSED}s" 2>&1 | grep -c 'ERROR')"
echo "═══════════════════════════"
