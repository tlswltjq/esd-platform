#!/usr/bin/env bash
# Outbox 릴레이 지표를 k6 와 같은 타임라인으로 수집한다.
#
#   ./scripts/perf/collect-outbox.sh baseline.csv &
#   k6 run scripts/perf/order-throughput.js
#   kill %1
#
# k6 는 HTTP 만 본다. 릴레이는 배경 스레드라 별도 수집이 필요하고,
# DB 를 직접 폴링하면 측정이 측정 대상에 부하를 준다 — actuator 를 긁는다.
#
# 출력 CSV 는 누적 카운터가 아니라 구간 증분(초당 처리량)까지 계산해 둔다.
# 개선 전후 비교에서 실제로 보게 되는 값이 그쪽이다.
set -euo pipefail

OUT="${1:-outbox-metrics.csv}"
ENDPOINT="${ORDER_ACTUATOR:-http://localhost:8082/actuator/prometheus}"
INTERVAL="${INTERVAL:-1}"

metric() {
  # prometheus 노출 형식은 이름 뒤에 라벨이 붙는다: name{application="order"} 12.0
  # 이름만으로 비교하면 절대 안 맞는다. 라벨을 허용하고 마지막 필드를 값으로 읽는다.
  local value
  value=$(grep -m1 -E "^$1(\{[^}]*\})?[[:space:]]" <<<"$2" | awk '{ print $NF }')
  echo "${value:-0}"
}

echo "elapsed_s,published_total,failed_total,dead_total,pending,published_per_s" > "$OUT"
echo "수집 시작 → $OUT  (대상: $ENDPOINT, 주기: ${INTERVAL}s)" >&2

started=$(date +%s)
prev_published=""

while true; do
  if ! body=$(curl -sf --max-time 2 "$ENDPOINT" 2>/dev/null); then
    echo "actuator 응답 없음 — 서비스가 떠 있는지 확인" >&2
    sleep "$INTERVAL"
    continue
  fi

  published=$(metric "stove_outbox_published_total" "$body")
  failed=$(metric "stove_outbox_failed_total" "$body")
  dead=$(metric "stove_outbox_dead_total" "$body")
  pending=$(metric "stove_outbox_pending" "$body")
  elapsed=$(( $(date +%s) - started ))

  if [ -n "$prev_published" ]; then
    rate=$(awk -v a="$published" -v b="$prev_published" -v i="$INTERVAL" 'BEGIN { printf "%.1f", (a-b)/i }')
  else
    rate=""
  fi
  prev_published="$published"

  echo "${elapsed},${published},${failed},${dead},${pending},${rate}" >> "$OUT"
  sleep "$INTERVAL"
done
