#!/usr/bin/env bash
# 컨슈머 측 지표를 k6 와 같은 타임라인으로 수집한다.
#
#   ./scripts/perf/collect-consumer.sh fanout-consumer.csv &
#   k6 run scripts/perf/payment-callback.js
#   kill %1
#
# ── 왜 따로 있나 ──────────────────────────────────────────────────────
#
# `collect-outbox.sh` 는 **보내는 쪽**만 본다. 그래서 지금까지의 측정은 전부 생산자 쪽이었고,
# "릴레이가 초당 몇 건 발행하나" 는 알아도 **"받는 쪽이 그걸 따라오나"** 는 아무도 재지 않았다.
#
# 둘은 같은 숫자가 아니다. 발행이 초당 132건이어도 컨슈머가 60건씩 처리하면 랙이 쌓인다.
# 그리고 그 랙은 `stove_outbox_pending` 에 **전혀 나타나지 않는다** — Outbox 입장에서는
# 이미 보냈으므로 0 이다. 적체가 브로커로 옮겨간 것뿐인데 생산자 지표만 보면 해소된 것처럼 보인다.
#
# ── 무엇을 긁나 ───────────────────────────────────────────────────────
#
# 전부 이미 노출돼 있는 것들이다. 새로 계측한 것은 없다.
#
#   kafka_consumer_fetch_manager_records_lag        아직 안 읽은 건수 (파티션별 → 합산)
#   kafka_consumer_fetch_manager_records_consumed_total   누적 소비 → 증분이 초당 소비량
#   spring_kafka_listener_seconds_count / _sum      리스너 처리 건수와 총 시간 → 평균
#   위 지표의 error 태그                             none 이 아닌 것 = 예외를 던진 건수
#
# 마지막 것이 특히 중요하다. 리스너가 던진 예외는 재시도로 흡수되므로 **HTTP 응답에도,
# 생산자 지표에도 나타나지 않는다.** 여기서만 보인다.
set -euo pipefail

OUT="${1:-consumer-metrics.csv}"
INTERVAL="${INTERVAL:-1}"

# `이름=주소` 목록. 기본값은 호스트에서 포트가 열린 경우다 —
# 9장 절차(전체 스택 컨테이너)에서는 서비스 이름으로 덮는다(README 환경 변수 표).
#
# 기본 목록은 **결제 콜백 팬아웃 경로**다. PaymentCompleted 가 셋으로 갈라지고
# license 가 낳은 LicenseIssued 가 download 까지 간다 — 컨슈머 측 부하가 가장 큰 모양이다.
CONSUMERS="${CONSUMERS:-payment=http://localhost:8083 order=http://localhost:8082 license=http://localhost:8084 settlement=http://localhost:8089 download=http://localhost:8088}"

# 라벨이 붙은 지표를 전부 더한다.
#
# 이름이 접두사로 겹치는 것들이 있어(records_lag vs records_lag_avg) **이름 바로 뒤가
# `{` 인 줄만** 센다. 그리고 NaN 을 건너뛴다 — 창(window) 기반 지표는 표본이 없을 때 NaN 을 내는데,
# awk 에서 그대로 더하면 합계 전체가 NaN 이 되어 그 회차가 통째로 사라진다.
sum() {
    local name=$1 fmt=$2 body=$3
    awk -v n="$name" -v f="$fmt" '
        index($0, n "{") == 1 {
            v = $NF
            if (v == "NaN" || v == "+Inf" || v == "-Inf") next
            s += v
        }
        END { printf f, s + 0 }
    ' <<<"$body"
}

# error 태그가 none 이 아닌 처리 건수 = 리스너가 예외를 던진 횟수.
listener_errors() {
    awk '
        index($0, "spring_kafka_listener_seconds_count{") == 1 && $0 !~ /error="none"/ {
            v = $NF
            if (v == "NaN") next
            s += v
        }
        END { printf "%.0f", s + 0 }
    ' <<<"$1"
}

echo "elapsed_s,app,lag,consumed_total,consumed_per_s,handled_total,avg_handle_ms,listener_errors" > "$OUT"
echo "수집 시작 → $OUT  (대상: ${CONSUMERS}, 주기: ${INTERVAL}s)" >&2

started=$(date +%s)
declare -A prev_consumed

while true; do
    elapsed=$(( $(date +%s) - started ))

    for entry in $CONSUMERS; do
        app="${entry%%=*}"
        base="${entry#*=}"

        if ! body=$(curl -sf --max-time 2 "${base}/actuator/prometheus" 2>/dev/null); then
            echo "${app}: actuator 응답 없음 — 떠 있는지 확인" >&2
            continue
        fi

        lag=$(sum kafka_consumer_fetch_manager_records_lag '%.0f' "$body")
        consumed=$(sum kafka_consumer_fetch_manager_records_consumed_total '%.0f' "$body")
        handled=$(sum spring_kafka_listener_seconds_count '%.0f' "$body")
        handled_s=$(sum spring_kafka_listener_seconds_sum '%.6f' "$body")
        errors=$(listener_errors "$body")

        # 평균 처리시간. 건수가 0 이면 나눌 수 없으므로 빈 칸으로 둔다 —
        # 0 으로 적으면 "처리가 즉시 끝났다" 로 읽힌다.
        avg_ms=$(awk -v s="$handled_s" -v n="$handled" \
            'BEGIN { if (n > 0) printf "%.2f", s * 1000 / n }')

        rate=$(awk -v a="$consumed" -v b="${prev_consumed[$app]:-}" -v i="$INTERVAL" \
            'BEGIN { if (b != "") printf "%.1f", (a - b) / i }')
        prev_consumed[$app]="$consumed"

        echo "${elapsed},${app},${lag},${consumed},${rate},${handled},${avg_ms},${errors}" >> "$OUT"
    done

    sleep "$INTERVAL"
done
