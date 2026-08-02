#!/usr/bin/env bash
# Outbox 릴레이의 순수 처리량을 잰다.
#
#   ./scripts/perf/relay-drain.sh 20000 baseline
#
# 왜 HTTP 부하로 재지 않는가 —
# 주문 생성 경로는 catalog 동기 호출과 커넥션 풀 때문에 훨씬 낮은 지점에서 먼저 포화한다.
# 그 상태로는 릴레이가 충분히 밀리지 않아 "릴레이가 얼마나 빠른가"를 알 수 없다.
# 이벤트를 DB 에 직접 적재해 적체를 만들고, 그것이 비워지는 속도를 재면
# HTTP 경로와 무관하게 릴레이만 측정된다.
#
# 파티션 키를 전부 다르게 준다. 개선안(키 단위 웨이브 발행)이 키 분산에 따라
# 처리량이 달라지므로, 개선 전후 모두 같은 분포에서 재야 비교가 성립한다.
set -euo pipefail

COUNT="${1:-20000}"
LABEL="${2:-run}"
ENDPOINT="${ORDER_ACTUATOR:-http://localhost:8082/actuator/prometheus}"
DB_SCHEMA="${DB_SCHEMA:-stove_order}"
INTERVAL="${INTERVAL:-1}"
OUT="${OUT_DIR:-.}/relay-drain-${LABEL}.csv"

mysql_exec() {
  # 에러를 버리지 않는다 — 적재가 조용히 실패하면 측정 결과가 통째로 거짓이 된다.
  docker compose exec -T mysql mysql -ustove -pstove1234 -N -e "$1"
}

metric() {
  local value
  value=$(grep -m1 -E "^$1(\{[^}]*\})?[[:space:]]" <<<"$2" | awk '{ print $NF }')
  echo "${value:-0}"
}

scrape() { curl -sf --max-time 2 "$ENDPOINT"; }

echo "== 적체 생성: ${COUNT}건 =="
mysql_exec "TRUNCATE ${DB_SCHEMA}.outbox_event;"

# 재귀 CTE 로 한 번에 적재한다. 애플리케이션을 거치면 그쪽이 병목이 되어
# 적체를 원하는 만큼 빠르게 만들 수 없다.
# 재귀 CTE 기본 깊이는 1000 이라 그대로 두면 COUNT 가 넘는 순간 조용히 끊긴다.
mysql_exec "
SET SESSION cte_max_recursion_depth = $((COUNT + 10));
INSERT INTO ${DB_SCHEMA}.outbox_event
  (event_id, aggregate_type, aggregate_id, event_type, topic, partition_key,
   payload, status, retry_count, next_attempt_at, created_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < ${COUNT}
)
SELECT CONCAT('PERF-', UUID()),
       'Order',
       CONCAT('ORD-PERF-', n),
       'OrderCreated',
       'stove.order.v1',
       CONCAT('ORD-PERF-', n),
       JSON_OBJECT('orderNo', CONCAT('ORD-PERF-', n), 'memberId', 42, 'totalAmount', 39000),
       'PENDING', 0, NULL, NOW(6)
FROM seq;"

pending_start=$(mysql_exec "SELECT COUNT(*) FROM ${DB_SCHEMA}.outbox_event WHERE status='PENDING';")
echo "적체 확인: ${pending_start}건"

body=$(scrape) || { echo "actuator 응답 없음"; exit 1; }
published_start=$(metric "stove_outbox_published_total" "$body")

echo "elapsed_ms,pending,published_delta,rate_per_s" > "$OUT"
echo "== 배수 측정 시작 =="

start_ns=$(python3 -c 'import time; print(time.time_ns())')
prev_delta=0
prev_ms=0
zero_streak=0
seen_backlog=0

while true; do
  body=$(scrape) || continue
  pending=$(metric "stove_outbox_pending" "$body")
  published=$(metric "stove_outbox_published_total" "$body")
  now_ns=$(python3 -c 'import time; print(time.time_ns())')
  elapsed_ms=$(( (now_ns - start_ns) / 1000000 ))

  delta=$(awk -v a="$published" -v b="$published_start" 'BEGIN { printf "%d", a - b }')
  rate=$(awk -v d="$delta" -v pd="$prev_delta" -v ms="$elapsed_ms" -v pms="$prev_ms" \
      'BEGIN { dt = (ms - pms) / 1000.0; if (dt > 0) printf "%.1f", (d - pd) / dt; else print "" }')
  echo "${elapsed_ms},${pending%.*},${delta},${rate}" >> "$OUT"
  prev_delta="$delta"; prev_ms="$elapsed_ms"

  # 종료 판정은 pending 만 본다.
  # 발행 카운터로 판정하면, 적재 직후 첫 스크레이프 이전에 릴레이가 이미 몇 건을 보낸 경우
  # 기준점이 어긋나 영원히 끝나지 않는다(실제로 겪었다).
  if [ "${pending%.*}" = "0" ]; then
    zero_streak=$((zero_streak + 1))
  else
    seen_backlog=1
    zero_streak=0
  fi
  if [ "$seen_backlog" = "1" ] && [ "$zero_streak" -ge 3 ]; then
    break
  fi
  if [ "$elapsed_ms" -gt 600000 ]; then
    echo "10분 초과 — 중단"; break
  fi
  sleep "$INTERVAL"
done

# 종료 확인에 쓴 3회분을 빼야 실제 배수 시간이 된다
drained_ms=$(awk -v ms="$elapsed_ms" -v n="$zero_streak" -v i="$INTERVAL" \
    'BEGIN { printf "%d", ms - (n - 1) * i * 1000 }')
total_s=$(awk -v ms="$drained_ms" 'BEGIN { printf "%.1f", ms / 1000.0 }')
throughput=$(awk -v n="$pending_start" -v s="$total_s" 'BEGIN { printf "%.1f", n / s }')

echo
echo "== 결과 (${LABEL}) =="
echo "  이벤트      : ${pending_start} 건"
echo "  소요        : ${total_s} 초"
echo "  평균 처리량 : ${throughput} events/s"
echo "  상세        : ${OUT}"
