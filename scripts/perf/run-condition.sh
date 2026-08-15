#!/usr/bin/env bash
# `listener.concurrency` 한 조건을 **압박 위에서** 재는 회차 하나. 13-3 절차를 그대로 돈다.
#
#   ./scripts/perf/run-condition.sh 1 c1-r1
#   ./scripts/perf/run-condition.sh 3 c3-r1
#
#   조건당 최소 2회, 그리고 대조군으로 되돌아온다 — 순서는 `1 → 3 → 3 → 1`.
#
# ── 왜 run-session.sh 로는 안 되나 ───────────────────────────────────
#
# `run-session.sh` 는 시나리오 **하나**를 돈다. 그런데 이 비교는 부하가 둘이다 —
# order 60 RPS 로 컨슈머를 포화시켜 두고 그 위에 `fanout` 을 겹쳐야 한다.
# **겹치지 않으면 실험이 질문에 답하지 못한다**(13-2: 풀 active 3/20, 랙 최대 3,
# 즉 압박이 없는데 노브를 돌린 것이다).
#
# 그리고 회차마다 되돌려야 하는 것이 DB 만이 아니다. 오프셋까지다(규칙 6).
#
# ── 되돌렸다고 가정하지 않는다 ───────────────────────────────────────
#
# 초기화 명령의 종료 코드는 초기화됐다는 증거가 아니다. 실제로 두 번 데였다.
#
#   13-8   `--reset-offsets` 가 그룹에 활성 멤버가 남아 경합으로 실패 → 시작 랙 5,686
#   14-6   드라이버가 `GROUPS`(**bash 내장 변수**)를 써서 대입이 안 먹음.
#          존재하지 않는 그룹에 리셋이 `rc=0` 으로 성공  → 시작 랙 21,971
#
# 둘 다 성공률 0% 회차를 만들었고 둘 다 버려야 했다. 그래서 **센다** —
# 리셋 직후 한 번, 부하 직전 한 번. 두 번인 이유는 재기동 구간에서 랙이 다시 설 수 있어서다.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"

C="${1:-}"
LABEL="${2:-}"
if ! [[ "$C" =~ ^[0-9]+$ ]] || [ -z "$LABEL" ]; then
    cat >&2 <<'EOF'
사용법: ./scripts/perf/run-condition.sh <concurrency> <라벨>

  concurrency   payment 의 SPRING_KAFKA_LISTENER_CONCURRENCY. 파티션 수가 3이라 1~3
  라벨          결과 폴더 이름에 붙는다. 회차를 구분할 수 있게 (c1-r1, c3-r2, c1-recheck)

환경 변수
  BG_RATE       배경 부하 RPS (기본 60). **한계선의 절반 이하**여야 한다 (규칙 4)
  BG_DURATION   배경 부하 지속 (기본 150s). 짧으면 워밍업이 결과에 실린다 (13-7)
  OVERLAP_S     배경을 깔고 fanout 을 얹기까지 대기 (기본 25). 이 값이 결과를 크게 흔든다 — 14-4
EOF
    exit 1
fi

# 이름이 `RESET_GROUPS` 인 것은 취향이 아니다 — `GROUPS` 는 bash 내장 변수라
# 대입이 먹지 않는다. 14-6 이 그 회차를 통째로 버린 이유다.
APPS="payment license order"
RESET_GROUPS="payment license order"

BG_RATE="${BG_RATE:-60}"
BG_DURATION="${BG_DURATION:-150s}"
OVERLAP_S="${OVERLAP_S:-25}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-stove-kafka}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:19092}"

say() { printf '\033[35m▪\033[0m %s\n' "$*"; }
die() { echo "[$LABEL] $* — 중단. 더러운 상태로 잰 숫자는 쓸 수 없다" >&2; exit 1; }

kafka_groups() {
    docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server "$BOOTSTRAP" "$@" 2>/dev/null
}

# 출력 첫 줄이 빈 줄이라 헤더가 2행에 온다 — `NR>1` 로 거르면 헤더의 문자열 "STATE" 를
# 상태값으로 읽어 대기가 영원히 타임아웃한다. 실제로 그렇게 겪었다.
group_state() { kafka_groups --describe --group "$1" --state | awk 'NF && $1 != "GROUP" { print $(NF-1) }' | head -1; }

# 커밋 이력이 없는 파티션(`-`)은 0 이 아니라 미지수다. 합에서 뺀다 — collect-lag.sh 와 같은 계산.
group_lag() { kafka_groups --describe --group "$1" | awk '$3 ~ /^[0-9]+$/ && $4 != "-" { s += $5 - $4 } END { print s + 0 }'; }

# ── 1. 앱을 내리고 그룹이 실제로 빌 때까지 기다린다 ──────────────────
# `--reset-offsets` 는 그룹에 활성 멤버가 없어야 한다. `stop` 직후 브로커는 멤버를
# 아직 안 떨어냈고, **기다렸다고 가정한 것**이 13-8 이 버린 회차다. 여기서는 확인한다.
say "[$LABEL] 앱 정지 — $APPS"
docker compose -f docker-compose.apps.yml stop $APPS >/dev/null 2>&1

for g in $RESET_GROUPS; do
    state=""
    for i in $(seq 1 60); do
        state=$(group_state "$g")
        [ "$state" = "Empty" ] && break
        sleep 1
    done
    [ "$state" = "Empty" ] || die "그룹 $g 가 60초 뒤에도 '$state' 다"
    say "[$LABEL] 그룹 $g 비었다 (${i}s)"
done

# ── 2. 오프셋을 로그 끝으로 — 그리고 되돌아갔는지 센다 ───────────────
for g in $RESET_GROUPS; do
    out=$(kafka_groups --group "$g" --reset-offsets --to-latest --all-topics --execute) \
        || { echo "$out" | head -3 >&2; die "$g 오프셋 리셋 실패"; }
    lag=$(group_lag "$g")
    say "[$LABEL] $g 리셋 후 랙=$lag"
    [ "$lag" -le 100 ] || die "$g 랙이 리셋 뒤에도 $lag 다"
done

# ── 3. 스키마 ───────────────────────────────────────────────────────
docker compose exec -T mysql mysql -ustove -pstove1234 -e "
    SET FOREIGN_KEY_CHECKS=0;
    TRUNCATE stove_order.outbox_event;   TRUNCATE stove_order.processed_event;
    TRUNCATE stove_order.order_item;     TRUNCATE stove_order.orders;
    TRUNCATE stove_payment.outbox_event; TRUNCATE stove_payment.processed_event;
    TRUNCATE stove_payment.payment;
    TRUNCATE stove_license.outbox_event; TRUNCATE stove_license.processed_event;
    TRUNCATE stove_license.license;
    SET FOREIGN_KEY_CHECKS=1;" 2>/dev/null || die "스키마 초기화 실패"

# ── 4. 조건을 걸고 재기동 ───────────────────────────────────────────
# 오버라이드 파일로 준다 — `relay-off.override.yml` 과 같은 방식이다.
# 이 값이 **실제로 걸렸는지**는 run-session.sh 가 컨테이너 환경변수를 훑어 env.txt 에 남긴다(규칙 7).
override=$(mktemp -t stove-concurrency)
cat > "$override" <<EOF
name: stove-apps
services:
  payment:
    environment:
      SPRING_KAFKA_LISTENER_CONCURRENCY: "${C}"
EOF
trap 'rm -f "$override"' EXIT

say "[$LABEL] concurrency=${C} 로 재기동"
docker compose -f docker-compose.apps.yml -f "$override" \
    up -d --force-recreate $APPS >/dev/null 2>&1 || die "재기동 실패"

for app in payment:8083 license:8084 order:8082; do
    port=${app#*:}
    for _ in $(seq 1 90); do
        curl -sf --max-time 2 "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q UP && break
        sleep 2
    done
done
say "[$LABEL] healthy"

# 재기동 직후 워밍업을 결과에 싣지 않는다. **13-7 이 12장을 뒤집은 원인이 이것이다** —
# 60초 측정이 JIT·풀 확보 구간을 p95 에 실었다. 얼마가 충분한지는 아직 안 정했다(measuring.md).
sleep 20

# 재기동 구간에서 랙이 다시 설 수 있으므로 한 번 더 센다. **정상은 300 안팎이다.**
start_lag=$(group_lag payment)
say "[$LABEL] 부하 직전 payment 랙=${start_lag}"
[ "$start_lag" -le 500 ] || die "시작 랙 ${start_lag} — 13-8 이 버린 회차와 같은 모양이다"

# ── 5. 배경 부하 — 컨슈머를 포화시킨다 ──────────────────────────────
say "[$LABEL] 배경 soak ${BG_RATE} RPS ${BG_DURATION}"
bg_log="$(mktemp -t stove-soak)"
docker run --rm --network "${PERF_NETWORK:-stove_default}" -v "$REPO:/w" -w /w \
    -e ORDER_URL=http://order:8082 -e CATALOG_URL=http://catalog:8081 \
    -e "RATE=${BG_RATE}" -e "DURATION=${BG_DURATION}" \
    grafana/k6 run --quiet scripts/perf/order-soak.js > "$bg_log" 2>&1 &
bg_pid=$!

# **얼마나 밀린 위에서 재는가가 결과를 바꾼다.** 곧바로 겹치면 아직 안 밀린 구간이 표본에 섞여
# 앞쪽 반복이 한도 안에 들어온다 — 13장(거의 동시 출발)이 c=1 에서 50% 를 본 이유고,
# 여기(25초 뒤)가 10~20% 를 본 이유다. 14-4. 회차 사이에 이 값을 바꾸지 않는다.
sleep "$OVERLAP_S"

# ── 6. 겹쳐서 fanout — 수집기·env.txt 는 run-session.sh 가 맡는다 ────
say "[$LABEL] fanout 겹침"
./scripts/perf/run-session.sh fanout "$LABEL"
rc=$?

wait $bg_pid 2>/dev/null
say "[$LABEL] 배경 soak 결과 — 이 줄이 '부하가 실제로 걸렸다'의 증거다 (규칙 2)"
grep -E "checks_succeeded|dropped_iterations|http_req_duration" "$bg_log" | sed "s/^/  /"
rm -f "$bg_log"

# k6 임계값 실패(99)를 그대로 넘긴다. c=1 은 e2e_fulfillment_ok 에서 실패하는 것이 정상이다 —
# **그 실패가 이 측정이 찾는 결과다.**
exit $rc
