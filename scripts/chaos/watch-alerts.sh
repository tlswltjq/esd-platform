#!/usr/bin/env bash
#
# 알람 타임라인 수집기 — 장애를 넣은 순간부터 **알람이 실제로 울릴 때까지**를 잰다.
#
#   scripts/chaos/watch-alerts.sh --out runs/alerts.csv --duration 900 &
#
# ── 왜 필요한가 ─────────────────────────────────────────────────────
#
# 알람 규칙이 있다는 것과 알람이 울린다는 것은 다르다. 규칙은 `promtool check rules` 로
# 문법만 확인되고, 임계·`for`·창 길이가 실제로 뜻하는 시간은 **울려 봐야 안다.**
# 이 저장소는 그걸 한 번 놓쳤다 — `ConsumerStalled` 가 `delta(...[5m])` 에 `for: 5m` 을 겹쳐 달아
# 실제 발화가 10분인데 description 은 5분이라고 말하고 있었다(#35 에서 고쳤지만 **실측은 없었다**).
#
# 그리고 D-027 이 보상 환불 대신 보류(DLT)를 택하면서 **알람이 안전의 전제**가 됐다.
# "되돌릴 수 있다"는 사람이 제때 본다는 뜻이고, 제때가 몇 분인지는 이 스크립트가 답한다.
#
# ── 무엇을 남기나 ───────────────────────────────────────────────────
#
#   t_sec,alert,state,value,lag_sum,committed_offset_sum
#
# 알람 상태(`inactive`/`pending`/`firing`)와 랙을 **같은 타임라인**에 적는다.
# 나눠 두면 "랙이 언제 올랐나"와 "알람이 언제 울렸나"를 눈으로 맞춰야 하고,
# 그 맞춤이 곧 재려던 값이다.
set -uo pipefail

PROM=${PROM:-http://localhost:9090}
OUT=""
DURATION=900
INTERVAL=${INTERVAL:-10}
GROUP=${LAG_GROUP:-license}

while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT=$2; shift 2 ;;
        --duration) DURATION=$2; shift 2 ;;
        --interval) INTERVAL=$2; shift 2 ;;
        --group) GROUP=$2; shift 2 ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) echo "모르는 옵션: $1" >&2; exit 1 ;;
    esac
done
[ -n "$OUT" ] || { echo "--out 은 필수다" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"

# 스칼라 하나를 뽑는다. 시계열이 없으면 0 이 아니라 `-` 다 —
# **없는 것과 0 인 것은 다르다.** 0 으로 적으면 "지표가 안 붙었다"가 "적체 없음"으로 읽힌다.
scalar() {
    curl -s -m 5 --get "$PROM/api/v1/query" --data-urlencode "query=$1" 2>/dev/null \
        | python3 -c '
import sys,json
try:
    r=json.load(sys.stdin)["data"]["result"]
    print(r[0]["value"][1] if r else "-")
except Exception:
    print("-")'
}

# 규칙에 걸린 알람의 현재 상태. 울리지 않는 동안에도 규칙 이름을 남겨야
# "언제부터 조용했나"를 나중에 읽을 수 있다.
alert_states() {
    curl -s -m 5 "$PROM/api/v1/rules?type=alert" 2>/dev/null | python3 -c '
import sys,json
try:
    d=json.load(sys.stdin)["data"]["groups"]
except Exception:
    sys.exit(0)
for g in d:
    for r in g.get("rules",[]):
        if r.get("type")!="alerting": continue
        insts=r.get("alerts") or []
        if not insts:
            print("%s\tinactive\t-" % r["name"]); continue
        for a in insts:
            print("%s\t%s\t%s" % (r["name"], a.get("state","?"), a.get("value","-")))'
}

echo "t_sec,alert,state,value,lag_sum,committed_offset_sum" > "$OUT"
START=$(date +%s)
echo "[$(date +%H:%M:%S)] 알람 타임라인 수집 시작 → $OUT (${DURATION}s, ${INTERVAL}s 간격)"

LAST_FIRING=""
while [ $(( $(date +%s) - START )) -lt "$DURATION" ]; do
    T=$(( $(date +%s) - START ))
    LAG=$(scalar "sum(kafka_consumergroup_lag_sum{consumergroup=\"$GROUP\"})")
    OFF=$(scalar "sum(kafka_consumergroup_current_offset_sum{consumergroup=\"$GROUP\"})")

    STATES=$(alert_states)
    while IFS=$'\t' read -r name state value; do
        [ -n "$name" ] || continue
        echo "$T,$name,$state,$value,$LAG,$OFF" >> "$OUT"
    done <<< "$STATES"

    # 새로 울린 것만 화면에 알린다 — 10초마다 같은 줄이 반복되면 사람이 읽지 않는다.
    FIRING=$(echo "$STATES" | awk -F'\t' '$2=="firing"{print $1}' | sort -u | paste -sd, -)
    if [ "$FIRING" != "$LAST_FIRING" ]; then
        echo "  +${T}s  울림=[${FIRING:-없음}]  랙=$LAG  커밋오프셋=$OFF"
        LAST_FIRING=$FIRING
    fi
    sleep "$INTERVAL"
done

echo "[$(date +%H:%M:%S)] 수집 종료"

# ── 요약: 각 알람이 처음 pending/firing 이 된 시각 ───────────────────
echo
echo "════════ 알람별 첫 발화 (장애 주입 시점 기준이 아니라 수집 시작 기준) ════════"
awk -F, 'NR>1 && ($3=="pending" || $3=="firing") && !seen[$2"_"$3]++ {printf "%-24s %-8s +%ss\n", $2, $3, $1}' "$OUT" \
    | sort -k3 -t+ -n
echo "══════════════════════════════════════════════════════════════════"
