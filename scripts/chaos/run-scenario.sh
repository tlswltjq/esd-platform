#!/usr/bin/env bash
#
# 장애 회차 러너 — 결제를 흘리는 도중에 장애를 넣고, 주문 하나하나의 **최종 상태**를 센다.
#
#   scripts/chaos/run-scenario.sh --fault license-table-denied --orders 40 --rate 2 \
#                                 --inject-after 5 --hold 60 --out runs/1c-table
#
# ── 무엇을 재는가 ───────────────────────────────────────────────────
#
# 처리량이 아니다. **돈과 물건이 짝이 맞는가**다. 주문 하나의 결말은 넷 중 하나다.
#
#   fulfilled     결제 PAID + 라이선스 있음      정상
#   parked        결제 PAID + 라이선스 없음      돈은 받고 물건은 아직 — 보류(DLT)에 있어야 한다
#   refunded      결제 CANCELED + 라이선스 없음  환불됨 — 장애가 원인이면 **오지급 환불**이다
#   inconsistent  결제 CANCELED + 라이선스 있음  환불했는데 물건은 줬다 — 가장 나쁘다
#
# 판정을 API 폴링이 아니라 **DB 에서 직접** 읽는다. 폴링은 "제한 시간 안에 보였는가"를 재는데,
# 여기서 물어야 하는 것은 "결국 어떻게 끝났는가"다. 둘은 장애 중에 특히 크게 갈린다 —
# 60초 안에 안 보였다고 실패로 세면, 5분 뒤 정상 지급된 주문까지 실패로 적힌다.
#
# ── 회차를 시작하기 전에 세는 것들 ──────────────────────────────────
#
# 사후에 env.txt 로 남기면 **버릴 수 있게** 되고, 사전에 세면 **낭비하지 않게** 된다.
# 오염된 회차 하나가 러너 하나보다 비싸다. 그래서 시작 상태를 부하 전에 검사하고,
# 조건에 안 맞으면 회차를 시작하지 않는다.
set -uo pipefail

ORDER_URL=${ORDER_URL:-http://localhost:8082}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8083}
LICENSE_URL=${LICENSE_URL:-http://localhost:8084}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-stove-mysql}
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-root1234}
KAFKA_CONTAINER=${KAFKA_CONTAINER:-stove-kafka}
KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP:-localhost:19092}
PRODUCT_ID=${PRODUCT_ID:-1}

FAULT=""
ORDERS=40
RATE=2
INJECT_AFTER=5
HOLD=60
DRAIN=90
OUT_DIR=""
REPEAT=1
CONTROL=no

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

usage() {
    cat <<'EOF'
scripts/chaos/run-scenario.sh --fault <이름> [옵션]

  --fault <이름>        주입할 장애. none 이면 대조군(장애 없이 같은 부하)
  --orders <n>          주문 건수 (기본 40)
  --rate <n>            초당 주문 건수 (기본 2)
  --inject-after <초>   부하 시작 후 몇 초에 주입할 것인가 (기본 5)
  --hold <초>           장애를 유지하는 시간 (기본 60)
  --drain <초>          복구 후 종단 상태가 굳기를 기다리는 시간 (기본 90)
  --out <디렉터리>      결과를 남길 곳
  --repeat <n>          같은 조건을 n 회 반복한다 (기본 1)
  --control             회차마다 바로 앞에 --fault none 대조군을 하나씩 붙인다
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --fault) FAULT=$2; shift 2 ;;
        --orders) ORDERS=$2; shift 2 ;;
        --rate) RATE=$2; shift 2 ;;
        --inject-after) INJECT_AFTER=$2; shift 2 ;;
        --hold) HOLD=$2; shift 2 ;;
        --drain) DRAIN=$2; shift 2 ;;
        --out) OUT_DIR=$2; shift 2 ;;
        --repeat) REPEAT=$2; shift 2 ;;
        --control) CONTROL=yes; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "모르는 옵션: $1" >&2; usage; exit 1 ;;
    esac
done

[ -n "$FAULT" ] || { echo "--fault 는 필수다 (장애 없이 재려면 --fault none)" >&2; exit 1; }
OUT_DIR=${OUT_DIR:-runs/$(date +%Y%m%d-%H%M%S)-$FAULT}
mkdir -p "$OUT_DIR"

# ── 회차 반복 ───────────────────────────────────────────────────────
#
# **왜 스크립트가 반복하는가** — measuring.md 는 "조건당 2회 + 대조군" 을 규칙으로 두는데,
# chaos 쪽은 손으로 돌리는 동안 그걸 못 지켰다(조건당 1회, 대조군은 전체에 한 번).
# 규칙을 지키는 데 사람의 성실함이 필요하면 그 규칙은 지켜지지 않는다.
#
# **대조군을 회차마다 붙이는 이유** — 전체에 한 번 붙이면 대조군과 장애 회차 사이에
# 스택 상태가 흘러간다(DLT 누적, 컨슈머 리밸런싱, 캐시). 바로 앞에 붙여야 그 둘의 차이가
# 장애 때문이라고 말할 수 있다.
#
# 자기 자신을 다시 부른다. 한 회차의 절차(사전 확인 → 부하 → 주입 → 판정)를 함수로 접으면
# 회차 사이에 상태가 새는데, **프로세스를 갈면 시작 상태를 다시 세는 것이 강제된다** —
# 이 스크립트가 사전 확인을 본체로 삼는 이유와 같다.
if [ "$REPEAT" -gt 1 ] || [ "$CONTROL" = yes ]; then
    pass_through=(--orders "$ORDERS" --rate "$RATE" --inject-after "$INJECT_AFTER" --drain "$DRAIN")
    rc=0
    for k in $(seq 1 "$REPEAT"); do
        if [ "$CONTROL" = yes ]; then
            echo "══ 회차 $k / $REPEAT — 대조군(장애 없음) ══"
            # 대조군은 유지 시간이 없다. 뺄 장애가 없으므로 --hold 는 그냥 기다리는 시간이 된다.
            bash "$0" --fault none "${pass_through[@]}" --hold 0 --out "$OUT_DIR/round-$k-control" || rc=$?
        fi
        echo "══ 회차 $k / $REPEAT — $FAULT ══"
        bash "$0" --fault "$FAULT" "${pass_through[@]}" --hold "$HOLD" --out "$OUT_DIR/round-$k" || rc=$?
    done

    # 회차별 결말을 나란히 놓는다. **편차를 눈으로 맞추지 않게 하는 것이 이 표의 목적이다** —
    # 1회차와 2회차가 다르면 그 차이가 잡음인지 조건인지부터 물어야 한다.
    {
        echo "조건   $FAULT · $ORDERS 건 @ $RATE/s · 유지 ${HOLD}s · 드레인 ${DRAIN}s"
        echo "회차   $REPEAT 회$([ "$CONTROL" = yes ] && echo " (각 회차 앞에 대조군)")"
        echo
        printf "%-22s %s\n" "회차" "결말"
        for d in "$OUT_DIR"/round-*; do
            [ -f "$d/summary.csv" ] || { printf "%-22s %s\n" "$(basename "$d")" "(무효 — summary.csv 없음)"; continue; }
            printf "%-22s %s\n" "$(basename "$d")" "$(tr '\n' ' ' < "$d/summary.csv")"
        done
    } | tee "$OUT_DIR/rounds.txt"
    echo "회차 종합: $OUT_DIR/rounds.txt"
    exit $rc
fi

sql() { docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "$1" 2>/dev/null; }

# DLT 는 이 회차 동안만 쌓이므로 끝 오프셋의 합이 곧 누적 건수다.
dlt_depth() {
    local topic=$1
    docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-get-offsets.sh \
        --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$topic" 2>/dev/null \
        | awk -F: '{s+=$3} END {print s+0}'
}

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT_DIR/run.log"; }

# ── 0. 사전 확인 ────────────────────────────────────────────────────

log "── 사전 확인 ──"
for svc in "$ORDER_URL" "$PAYMENT_URL" "$LICENSE_URL"; do
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$svc/actuator/health")
    [ "$code" = 200 ] || { log "  ✗ $svc 가 건강하지 않다 (HTTP $code). 회차를 시작하지 않는다."; exit 2; }
done
log "  ✓ order · payment · license 건강"

# 장애가 이미 걸려 있으면 '주입 전' 이라는 기준선 자체가 없다.
if [ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$LICENSE_URL/api/v1/library" -H 'X-Member-Id: 1')" != 200 ]; then
    log "  ✗ license 가 이미 장애 상태다. 지난 회차가 복구되지 않았다 — fault.sh heal 을 먼저 돌린다."
    exit 2
fi
log "  ✓ 장애 미주입 상태에서 출발"

BASE_LICENSE=$(sql "select count(*) from stove_license.license;")
BASE_PAID=$(sql "select count(*) from stove_payment.payment where status='PAID';")
BASE_CANCELED=$(sql "select count(*) from stove_payment.payment where status='CANCELED';")
BASE_DLT=$(dlt_depth stove.payment.v1.DLT)
BASE_OUTBOX=$(sql "select count(*) from stove_license.outbox_event where status<>'SENT';")
log "  시작 상태 license=$BASE_LICENSE PAID=$BASE_PAID CANCELED=$BASE_CANCELED DLT=$BASE_DLT outbox미발행=$BASE_OUTBOX"

{
    echo "생성       $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "장애       $FAULT"
    echo "주문       $ORDERS 건 @ $RATE/s"
    echo "타임라인   +${INJECT_AFTER}s 주입 · ${HOLD}s 유지 · 복구 후 ${DRAIN}s 드레인"
    echo "상품       productId=$PRODUCT_ID"
    echo "시작상태   license=$BASE_LICENSE PAID=$BASE_PAID CANCELED=$BASE_CANCELED DLT=$BASE_DLT"
    echo "호스트     $(uname -srm)"
} > "$OUT_DIR/env.txt"

# ── 1. 구매 드라이버 ────────────────────────────────────────────────
#
# 주문 하나의 전 구간을 한 서브셸이 담당한다. 승인 콜백까지가 '사용자가 돈을 낸 시점' 이고,
# 그 뒤는 시스템의 몫이라 드라이버는 기다리지 않는다 — 판정은 끝나고 DB 에서 한다.

purchase() {
    # 한 줄로 `local seq=$1 member=$((800000 + seq))` 이라고 쓰면 안 된다 — 워드 확장이
    # 대입보다 먼저 끝나므로 오른쪽의 seq 는 아직 없는 변수다. set -u 아래에서는 그 자리에서
    # 죽고, 없으면 전원 memberId=800000 으로 조용히 뭉친다. (첫 회차를 이걸로 통째로 버렸다.)
    local seq=$1
    local member=$((800000 + seq))
    local order no amt

    order=$(curl -s -m 10 -X POST "$ORDER_URL/api/v1/orders" -H 'Content-Type: application/json' \
        -d "{\"memberId\":$member,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}]}")
    no=$(echo "$order" | sed -n 's/.*"orderNo":"\([^"]*\)".*/\1/p')
    amt=$(echo "$order" | sed -n 's/.*"totalAmount":\([0-9]*\).*/\1/p')
    [ -n "$no" ] || { echo "$seq,,$member,ORDER_FAILED" >> "$OUT_DIR/orders.csv"; return; }

    # 결제 대기 레코드는 OrderCreated 가 Kafka 를 건너야 생긴다.
    local deadline=$((SECONDS + 30)) ready=no
    while [ $SECONDS -lt $deadline ]; do
        if curl -s -m 5 "$PAYMENT_URL/api/v1/payments/$no" | grep -q '"status":"READY"'; then ready=yes; break; fi
        sleep 0.3
    done
    [ "$ready" = yes ] || { echo "$seq,$no,$member,NO_PAYMENT_READY" >> "$OUT_DIR/orders.csv"; return; }

    curl -s -m 10 -X POST "$PAYMENT_URL/api/v1/payments/$no/prepare" \
        -H 'Content-Type: application/json' -d '{"method":"CARD"}' > /dev/null

    local paid_at; paid_at=$(date +%s)
    local cb; cb=$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "$PAYMENT_URL/api/v1/payments/callback" \
        -H 'Content-Type: application/json' \
        -d "{\"result\":\"APPROVED\",\"orderNo\":\"$no\",\"pgTxId\":\"PG-CHAOS-$no\",\"paidAmount\":$amt,\"idempotencyKey\":\"IDEM-CHAOS-$no\"}")
    echo "$seq,$no,$member,$([ "$cb" = 200 ] && echo APPROVED || echo "CALLBACK_$cb"),$paid_at" >> "$OUT_DIR/orders.csv"
}

log "── 부하 시작 ($ORDERS 건 @ $RATE/s) ──"
: > "$OUT_DIR/orders.csv"
START=$(date +%s)
INJECTED=no

for i in $(seq 1 "$ORDERS"); do
    purchase "$i" &
    # 주입 시점은 '몇 번째 주문' 이 아니라 '부하 시작 후 몇 초' 로 잡는다.
    # 건수로 잡으면 장애 중에 드라이버가 느려질 때 주입 시점이 같이 밀린다.
    if [ "$INJECTED" = no ] && [ $(( $(date +%s) - START )) -ge "$INJECT_AFTER" ]; then
        INJECTED=yes
        if [ "$FAULT" != none ]; then
            log "── 장애 주입 (+$(( $(date +%s) - START ))s) ──"
            if ! bash "$HERE/fault.sh" inject "$FAULT" 2>&1 | tee -a "$OUT_DIR/run.log"; then
                log "  ✗ 주입 실패 — 회차를 버린다"
                wait; exit 2
            fi
            INJECT_AT=$(date +%s)
        else
            log "── 대조군: 장애를 주입하지 않는다 ──"
            INJECT_AT=$(date +%s)
        fi
    fi
    sleep "$(awk -v r="$RATE" 'BEGIN{print 1/r}')"
done

# 아직 주입 시점에 도달하지 못한 채 주문이 끝났으면 지금 넣는다.
if [ "$INJECTED" = no ] && [ "$FAULT" != none ]; then
    log "── 장애 주입 (부하 종료 직후) ──"
    bash "$HERE/fault.sh" inject "$FAULT" 2>&1 | tee -a "$OUT_DIR/run.log" || { wait; exit 2; }
    INJECT_AT=$(date +%s)
fi

wait
log "  부하 종료 (+$(( $(date +%s) - START ))s)"

# ── 2. 장애 유지 ────────────────────────────────────────────────────

if [ "$FAULT" != none ]; then
    ELAPSED=$(( $(date +%s) - INJECT_AT ))
    REMAIN=$(( HOLD - ELAPSED ))
    [ $REMAIN -gt 0 ] && { log "── 장애 유지 ${REMAIN}s 더 ──"; sleep "$REMAIN"; }

    MID_DLT=$(dlt_depth stove.payment.v1.DLT)
    MID_CANCELED=$(sql "select count(*) from stove_payment.payment where status='CANCELED';")
    log "  장애 중 DLT=$(( MID_DLT - BASE_DLT )) 취소=$(( MID_CANCELED - BASE_CANCELED ))"

    log "── 복구 ──"
    bash "$HERE/fault.sh" heal "$FAULT" 2>&1 | tee -a "$OUT_DIR/run.log"
    HEAL_AT=$(date +%s)
fi

# ── 3. 드레인 ───────────────────────────────────────────────────────
#
# 복구 뒤에도 재시도·릴레이·보상이 한동안 더 움직인다. 그게 멎기 전에 세면
# '아직 안 끝난 것' 이 '실패' 로 적힌다.

log "── 드레인 ${DRAIN}s ──"
sleep "$DRAIN"

# ── 4. 판정 ─────────────────────────────────────────────────────────

log "── 판정 ──"
awk -F, 'NF>=4 && $2!="" {print $2}' "$OUT_DIR/orders.csv" | sort > "$OUT_DIR/ordernos.txt"
TOTAL=$(wc -l < "$OUT_DIR/ordernos.txt" | tr -d ' ')

IN_LIST=$(awk '{printf "%s'\''%s'\''", (NR>1?",":""), $0}' "$OUT_DIR/ordernos.txt")
[ -n "$IN_LIST" ] || { log "  주문이 하나도 만들어지지 않았다 — 회차 무효"; exit 2; }

sql "
select p.order_no,
       p.status,
       (select count(*) from stove_license.license l where l.order_no = p.order_no) as licenses
from stove_payment.payment p
where p.order_no in ($IN_LIST)
order by p.order_no;" > "$OUT_DIR/outcomes.tsv"

classify() {
    awk -F'\t' -v OFS=, '
        { status=$2; lic=$3+0
          if (status=="PAID"     && lic>0) c="fulfilled"
          else if (status=="PAID"     && lic==0) c="parked"
          else if (status=="CANCELED" && lic==0) c="refunded"
          else if (status=="CANCELED" && lic>0)  c="inconsistent"
          else c="other:" status
          n[c]++ }
        END { for (k in n) print k, n[k] }' "$OUT_DIR/outcomes.tsv" | sort
}

classify > "$OUT_DIR/summary.csv"

END_DLT=$(dlt_depth stove.payment.v1.DLT)
END_LICENSE_DLT=$(dlt_depth stove.license.v1.DLT)
END_OUTBOX=$(sql "select count(*) from stove_license.outbox_event where status<>'SENT';")

{
    echo "회차       $OUT_DIR"
    echo "장애       $FAULT"
    echo "주문 시도  $ORDERS · 승인 도달 $TOTAL"
    echo
    printf "%-14s %s\n" "결말" "건수"
    while IFS=, read -r k v; do printf "%-14s %s\n" "$k" "$v"; done < "$OUT_DIR/summary.csv"
    echo
    echo "DLT payment.v1   $(( END_DLT - BASE_DLT )) 건 (누적 $END_DLT)"
    echo "DLT license.v1   $END_LICENSE_DLT 건 (누적)"
    echo "license outbox 미발행 $END_OUTBOX 건"
} | tee "$OUT_DIR/report.txt" | tee -a "$OUT_DIR/run.log"

log "결과: $OUT_DIR/report.txt"
