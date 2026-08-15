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

# macOS 기본 bash 는 3.2 라 연관 배열(`declare -A`)이 없다 — 예전에는 여기서 그걸 썼고,
# 그래서 이 스크립트는 **맥에서 첫 줄만 쓰고 죽었다.** 원격(bash 5)에서만 돌려 몰랐다.
# 리포가 도구 설치를 요구하지 않기로 했으므로(decisions.md 12번) 키를 변수 이름으로 만든다.
kv_name() { printf 'kv_%s' "$(printf '%s' "$1" | tr -c 'A-Za-z0-9' '_')"; }
kv_get()  { eval "printf '%s' \"\${$1:-}\""; }
kv_set()  { eval "$1=\$2"; }

# `lag` 가 아니라 `lag_reported` 인 이유 — 이 값은 랙의 판정 근거가 되지 못한다.
# 컨슈머 클라이언트가 직전 fetch 에서 본 값이라 fetch 가 멈추면 갱신되지 않고,
# **실제 랙 113,517건 동안 0 을 보고했다**(D-026). 판정은 `collect-lag.sh` 로 한다.
# 그래도 계속 긁는 이유는 두 값을 나란히 놓는 것이 그 결함의 재현 증거이기 때문이다.
# **커넥션 풀도 여기서 본다.** 리스너 스레드와 HTTP 스레드는 같은 앱의 같은 HikariCP 풀을 쓴다 —
# `listener.concurrency` 를 올리면 그 앱의 API 가 함께 느려질 수 있고, 여태 그 인과를
# 지연 숫자로 추정만 했다([performance.md](../../docs/performance.md) 8-2 가 계속 열려 있던 항목).
#
#   pool_active    지금 빌려 나간 커넥션
#   pool_pending   커넥션을 **기다리는 스레드 수**  ← 이게 0 보다 크면 풀이 병목이다
#
# 뒤엣것이 판정값이다. active 가 최대치라도 pending 이 0 이면 풀은 충분한 것이고,
# pending 이 서면 그때부터 대기가 응답시간에 그대로 실린다.
echo "elapsed_s,app,lag_reported,consumed_total,consumed_per_s,handled_total,avg_handle_ms,listener_errors,pool_active,pool_idle,pool_pending,pool_max" > "$OUT"
echo "수집 시작 → $OUT  (대상: ${CONSUMERS}, 주기: ${INTERVAL}s)" >&2
echo "  랙 판정은 collect-lag.sh 로 한다 — 여기 lag_reported 는 믿을 수 없다(D-026)" >&2

started=$(date +%s)

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

        n_consumed=$(kv_name "consumed/${app}")
        rate=$(awk -v a="$consumed" -v b="$(kv_get "$n_consumed")" -v i="$INTERVAL" \
            'BEGIN { if (b != "") printf "%.1f", (a - b) / i }')
        kv_set "$n_consumed" "$consumed"

        # 풀 지표는 라벨이 pool="HikariPool-1" 하나뿐이라 sum 이 곧 그 값이다.
        # 앱이 풀을 안 쓰면(예: Mongo 만 쓰는 download) 지표가 없어 0 이 나온다.
        pool_active=$(sum hikaricp_connections_active '%.0f' "$body")
        pool_idle=$(sum hikaricp_connections_idle '%.0f' "$body")
        pool_pending=$(sum hikaricp_connections_pending '%.0f' "$body")
        pool_max=$(sum hikaricp_connections_max '%.0f' "$body")

        echo "${elapsed},${app},${lag},${consumed},${rate},${handled},${avg_ms},${errors},${pool_active},${pool_idle},${pool_pending},${pool_max}" >> "$OUT"
    done

    sleep "$INTERVAL"
done
