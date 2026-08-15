#!/usr/bin/env bash
# 컨슈머 랙을 **브로커에게 직접 물어** 수집한다.
#
#   ./scripts/perf/collect-lag.sh fanout-lag.csv &
#   k6 run scripts/perf/payment-callback.js
#   kill %1
#
# ── 왜 actuator 로는 안 되나 ──────────────────────────────────────────
#
# `collect-consumer.sh` 와 performance.md 11장이 랙의 주 판정 수단으로 쓰던
# `kafka_consumer_fetch_manager_records_lag` 는 **실제 랙 113,517건 동안 0 을 보고했다**
# (perf-tuning.md 4절, [D-026](../../docs/defects.md#d-026)).
#
# 그 지표는 컨슈머 클라이언트가 **직전 fetch 응답에서 본 것**을 그대로 노출한다.
# 그래서 fetch 가 돌지 않는 동안 — 컨슈머가 죽었거나, 리밸런싱 중이거나,
# 파티션이 폴 사이에 놀고 있으면 — 값이 갱신되지 않고, 표본이 없는 창은 NaN 이나
# 옛 값으로 남는다. **랙이 가장 큰 순간이 바로 그 지표가 가장 못 미더운 순간이다.**
#
# 브로커가 가진 커밋 오프셋과 로그 끝 오프셋의 차이는 컨슈머가 살아 있든 아니든 참이다.
# 그래서 판정은 여기서 한다.
#
# ── 무엇을 남기나 ────────────────────────────────────────────────────
#
#   lag                 log_end_offset - current_offset 의 파티션 합
#   consumed_per_s      current_offset 증분 → 실제 소비 속도
#   produced_per_s      log_end_offset 증분 → 실제 유입 속도
#
# 마지막 둘을 같이 남기는 이유 — **랙이 0 인 것과 유입이 없는 것은 다르다.**
# 랙만 보면 둘이 구분되지 않아 "따라잡고 있다"와 "아무것도 안 왔다"가 같은 그림이 된다.
# performance.md 11-2 의 "랙 0" 이 실은 후자였다(10 VU 는 컨슈머를 밀지 못한다).
#
#   unknown_partitions  커밋 오프셋이 없어 랙을 계산할 수 없는 파티션 수
#
# **모르는 것을 0 으로 적지 않는다.** 그 습관이 위의 결함을 만들었다.
# 커밋 이력이 없는 파티션(`CURRENT-OFFSET` 이 `-`)은 lag 합에서 빼고 여기 센다.
set -euo pipefail

OUT="${1:-lag-metrics.csv}"
INTERVAL="${INTERVAL:-1}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-stove-kafka}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:19092}"
# 비우면 --all-groups. 컨슈머 그룹 이름은 앱 이름과 같다(spring.kafka.consumer.group-id).
#
# 이름이 `LAG_GROUPS` 인 것은 취향이 아니다 — `GROUPS` 는 **bash 내장 변수**(현재 사용자의 gid 배열)라
# 그 이름을 쓰면 사용자가 아무것도 주지 않아도 값이 들어 있다. 실제로 그렇게 겪었다.
LAG_GROUPS="${LAG_GROUPS:-}"

if [ -n "$LAG_GROUPS" ]; then
    group_args=()
    for g in $LAG_GROUPS; do group_args+=(--group "$g"); done
else
    group_args=(--all-groups)
fi

# macOS 기본 bash 는 3.2 라 연관 배열(`declare -A`)이 없다.
# 이 리포는 도구 설치를 요구하지 않는 것을 원칙으로 두므로(decisions.md 12번)
# 키를 변수 **이름**으로 만들어 쓴다. 원격(bash 5)에서도 같은 코드가 돈다.
kv_name() { printf 'kv_%s' "$(printf '%s' "$1" | tr -c 'A-Za-z0-9' '_')"; }
kv_get()  { eval "printf '%s' \"\${$1:-}\""; }
kv_set()  { eval "$1=\$2"; }

describe() {
    docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server "$BOOTSTRAP" --describe "${group_args[@]}" 2>/dev/null
}

if ! describe >/dev/null; then
    echo "브로커에 물을 수 없다 — 컨테이너 '${KAFKA_CONTAINER}' 가 떠 있는지 확인" >&2
    exit 1
fi

echo "elapsed_s,group,topic,partitions,current_offset,log_end_offset,lag,consumed_per_s,produced_per_s,unknown_partitions" > "$OUT"
echo "수집 시작 → $OUT  (브로커: ${BOOTSTRAP}, 대상: ${LAG_GROUPS:-전체 그룹}, 주기: ${INTERVAL}s)" >&2

started=$(date +%s)

rate() {
    awk -v a="$1" -v b="${2:-}" -v t="$3" -v pt="${4:-}" 'BEGIN {
        if (b == "" || pt == "" || t <= pt) exit
        printf "%.1f", (a - b) / (t - pt)
    }'
}

while true; do
    elapsed=$(( $(date +%s) - started ))
    body=$(describe) || { sleep "$INTERVAL"; continue; }

    # 그룹 블록마다 헤더가 다시 나오고, 활성 멤버가 없으면 경고 줄이 섞인다.
    # 파티션 번호가 숫자인 줄만 데이터로 본다.
    #
    # `-` 는 커밋 이력이 없다는 뜻이지 0 이 아니다. 합에서 빼고 따로 센다.
    while IFS=, read -r group topic parts cur end lag unknown; do
        [ -n "$group" ] || continue

        now=$(date +%s)
        n_cur=$(kv_name "cur/${group}/${topic}")
        n_end=$(kv_name "end/${group}/${topic}")
        n_at=$(kv_name  "at/${group}/${topic}")
        prev_at=$(kv_get "$n_at")

        consumed_rate=$(rate "$cur" "$(kv_get "$n_cur")" "$now" "$prev_at")
        produced_rate=$(rate "$end" "$(kv_get "$n_end")" "$now" "$prev_at")
        kv_set "$n_cur" "$cur"; kv_set "$n_end" "$end"; kv_set "$n_at" "$now"

        echo "${elapsed},${group},${topic},${parts},${cur},${end},${lag},${consumed_rate},${produced_rate},${unknown}" >> "$OUT"
    done < <(awk '
        $3 ~ /^[0-9]+$/ {
            key = $1 SUBSEP $2
            parts[key]++
            end[key] += $5
            if ($4 == "-") { unknown[key]++; next }
            cur[key] += $4
            # LAG 열을 그대로 믿지 않고 다시 뺀다 — 두 오프셋이 권위 있는 값이고
            # LAG 는 그 차이를 브로커가 계산해 준 것뿐이다. 커밋이 없는 파티션이 섞이면
            # 열 합계와 오프셋 차이가 어긋나는데, 그때 맞는 쪽은 오프셋이다.
            lag[key] += $5 - $4
        }
        END {
            for (k in parts) {
                split(k, f, SUBSEP)
                printf "%s,%s,%d,%d,%d,%d,%d\n",
                    f[1], f[2], parts[k], cur[k] + 0, end[k] + 0, lag[k] + 0, unknown[k] + 0
            }
        }' <<<"$body" | sort -t, -k1,2)

    sleep "$INTERVAL"
done
