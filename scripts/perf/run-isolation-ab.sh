#!/usr/bin/env bash
# 릴레이가 REPEATABLE READ 에서 쥐는 갭 락이 쓰기 경로의 p95 를 끌어올리는가 — 회차 하나.
#
#   ./scripts/perf/run-isolation-ab.sh rr rr-1
#   ./scripts/perf/run-isolation-ab.sh rc rc-1
#
#   조건당 2회, 대조군으로 되돌아온다 — 순서는 `rr → rc → rc → rr` (measuring.md 규칙 1·9).
#
# ── 무엇을 묻는가 ────────────────────────────────────────────────────
#
# performance.md 9-3 은 릴레이 ON 이 쓰기 p95 에 약 19ms 를 얹는 것을 쟀고, 9-4 가 원인으로 짚은
# "같은 커넥션 풀" 은 13-5 의 측정(풀 pending 0)이 기각했다. technical-interview-guide.md R1 의 가설은
# **릴레이의 `FOR UPDATE SKIP LOCKED` 가 REPEATABLE READ 에서 넥스트키 락을 잡고, 그 트랜잭션이
# Kafka ack 를 기다리는 동안 주문 트랜잭션의 outbox INSERT 가 기다린다** 는 것이다
# (락 모양은 scripts/db-lab 실험 1 로 확인했다).
#
#   가설이 맞으면     rc 의 p95 가 rr 보다 낮고, rr 에서만 InnoDB 락 대기가 쌓인다
#   가설이 틀리면     p95 차이가 회차 편차 안에 있거나, 락 대기가 두 조건 모두 0 에 가깝다
#
# ── 무엇을 바꾸는가 ──────────────────────────────────────────────────
#
# order 서비스 커넥션의 기본 격리 수준 **하나**다(Hikari `transactionIsolation`). 릴레이만
# 바꾸려면 코드를 고쳐야 하지만, 주문 생성 부하에서 order 의 트랜잭션은 주문·outbox INSERT 와
# 스케줄러의 PK 단건 UPDATE(만료 스윕·ShedLock)뿐이고 범위를 잠그는 문장은 릴레이 조회 하나다.
# READ COMMITTED 에서 결과가 달라지는 경로가 없어서 설정으로 같은 질문에 답한다.
# 조건이 **실제로 걸렸는지**는 MySQL 에 커넥션별 격리 수준을 직접 물어 확인한다(규칙 7).
#
# ── 9-3 과 다른 점 ───────────────────────────────────────────────────
#
# 테이블을 비우지 않는다. 공용 스택의 데이터를 지우지 않으려는 선택이고, 대신 회차마다
# 초기 행 수를 남기고 A-B-B-A 순서로 누적 효과를 대조한다(규칙 6).
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"

MODE="${1:-}"
LABEL="${2:-}"
case "$MODE" in rr|rc) ;; *) echo "사용법: $0 <rr|rc> <라벨>" >&2; exit 1 ;; esac
[ -n "$LABEL" ] || { echo "라벨이 필요하다 (rr-1, rc-1 …)" >&2; exit 1; }

RATE="${RATE:-60}"              # 9-3 과 같은 값 — 한계선(132.5 RPS)의 절반 이하
DURATION="${DURATION:-90s}"     # 9-3 의 회차 규모(주문 5,398건 ≈ 60 RPS × 90초)
WARMUP="${WARMUP:-30s}"         # 재기동 직후 JIT·풀 확보 구간을 결과에 싣지 않는다(13-7)
NET="${PERF_NETWORK:-stove_default}"
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-root1234}   # docker-compose.yml 의 개발용 기본값 (scripts/chaos 와 같은 규약)
OUT="runs/r1-isolation/$(date -u +%Y%m%dT%H%M%SZ)-${LABEL}"
COMPOSE=(-f docker-compose.apps.yml -f docker-compose.apps.ci.yml -f docker-compose.apps.e2e.yml)

say() { printf '\033[35m▪\033[0m [%s] %s\n' "$LABEL" "$*"; }
die() { echo "[$LABEL] $* — 중단. 조건이 불확실한 회차의 숫자는 쓸 수 없다" >&2; exit 1; }
sql() { docker exec -i stove-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B 2>/dev/null; }

mkdir -p "$OUT"

# ── 1. 조건을 걸고 order 만 다시 만든다 ─────────────────────────────
override="$OUT/override.yml"
if [ "$MODE" = rc ]; then
    cat > "$override" <<'EOF'
name: stove-apps
services:
  order:
    environment:
      # spring.datasource.hikari.transaction-isolation — 풀이 만드는 커넥션의 기본 격리 수준
      SPRING_DATASOURCE_HIKARI_TRANSACTIONISOLATION: TRANSACTION_READ_COMMITTED
EOF
    COMPOSE+=(-f "$override")
    expected=READ-COMMITTED
else
    : > "$override"
    expected=REPEATABLE-READ
fi

say "order 재생성 (기대 격리 수준 $expected)"
docker compose "${COMPOSE[@]}" up -d --no-build --force-recreate order >/dev/null 2>&1 || die "재생성 실패"
for _ in $(seq 1 90); do
    [ "$(docker inspect stove-apps-order-1 --format '{{.State.Health.Status}}' 2>/dev/null)" = healthy ] && break
    sleep 2
done
[ "$(docker inspect stove-apps-order-1 --format '{{.State.Health.Status}}')" = healthy ] || die "order 가 healthy 가 아니다"

# ── 2. 조건이 실제로 걸렸는가 — 커넥션마다 MySQL 에 묻는다 ───────────
isolation_of_order_connections() {
    sql <<'SQL'
SELECT CONCAT(vt.VARIABLE_VALUE, ' x', COUNT(*))
  FROM performance_schema.threads t
  JOIN performance_schema.variables_by_thread vt USING (THREAD_ID)
 WHERE t.PROCESSLIST_USER = 'stove' AND t.PROCESSLIST_DB = 'stove_order'
   AND vt.VARIABLE_NAME = 'transaction_isolation'
 GROUP BY vt.VARIABLE_VALUE;
SQL
}
applied="$(isolation_of_order_connections | paste -sd ' ' -)"
say "order 커넥션 격리 수준: $applied"
case "$applied" in "$expected x"*) ;; *) die "기대($expected)와 다르다: $applied" ;; esac

# k6 이미지는 uid 12345 로 돈다. 결과 폴더는 이 스크립트를 부른 사용자 소유라 그대로 두면
# **요약 JSON 만 조용히 안 써진다** — 콘솔 요약은 멀쩡히 나와서 알아채기 어렵다(첫 회차가 그랬다).
k6() {
    docker run --rm --user "$(id -u):$(id -g)" --network "$NET" -v "$REPO:/w" -w /w \
        -e ORDER_URL=http://order:8082 -e CATALOG_URL=http://catalog:8081 "$@"
}

# ── 3. 초기 상태와 락 카운터 ────────────────────────────────────────
snapshot() {
    sql <<'SQL'
SELECT 'orders', COUNT(*) FROM stove_order.orders
UNION ALL SELECT 'outbox_sent', COUNT(*) FROM stove_order.outbox_event WHERE status = 'SENT'
UNION ALL SELECT 'outbox_pending', COUNT(*) FROM stove_order.outbox_event WHERE status = 'PENDING'
UNION ALL SELECT 'row_lock_waits', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_row_lock_waits'
UNION ALL SELECT 'row_lock_time_ms', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_row_lock_time';
SQL
}
{
    echo "# run-isolation-ab $LABEL  mode=$MODE  $(date -u +%FT%TZ)"
    echo "host: $(hostname) $(uname -m) nproc=$(nproc)"
    echo "load: $(cut -d' ' -f1-3 /proc/loadavg)"
    echo "ci runner: $(systemctl list-units --type=service --state=active 'actions.runner.*' --no-legend 2>/dev/null | wc -l | tr -d ' ') active (0 이어야 한다 — 규칙 11)"
    echo "rate=$RATE duration=$DURATION warmup=$WARMUP"
    echo "compose: ${COMPOSE[*]}"
    echo "order image: $(docker inspect stove-apps-order-1 --format '{{.Image}}')"
    echo "order env override: $(grep -v '^\s*#' "$override" | tr -s ' \n' ' ')"
    echo "applied isolation: $applied"
    echo "mysql: $(echo 'SELECT VERSION(), @@transaction_isolation;' | sql)"
    echo "--- restarted"; snapshot
} > "$OUT/env.txt"

# ── 4. 워밍업 (지연 수치는 버리고, 락 카운터는 따로 남긴다) ──────────
say "워밍업 ${RATE} RPS ${WARMUP}"
k6 -e "RATE=$RATE" -e "DURATION=$WARMUP" grafana/k6 run --quiet scripts/perf/order-soak.js >/dev/null 2>&1
# 워밍업이 끌어올린 부하가 가라앉을 시간. 회차마다 같은 값을 쓴다 — 조건 사이 비교가 목적이다.
sleep "${COOLDOWN_S:-60}"
{ echo "--- before (load $(cut -d' ' -f1-3 /proc/loadavg))"; snapshot; } >> "$OUT/env.txt"
sed 's/^/  /' "$OUT/env.txt"

# ── 5. 측정 ─────────────────────────────────────────────────────────
say "측정 ${RATE} RPS ${DURATION}"
k6 -e "RATE=$RATE" -e "DURATION=$DURATION" grafana/k6 run --quiet \
    --summary-trend-stats='avg,min,med,max,p(90),p(95),p(99)' \
    --summary-export="/w/$OUT/k6-summary.json" scripts/perf/order-soak.js > "$OUT/k6.log" 2>&1
k6_rc=$?
sleep 5
{ echo "--- after"; snapshot; } >> "$OUT/env.txt"

# ── 6. 요약 ─────────────────────────────────────────────────────────
python3 - "$OUT" "$k6_rc" <<'PY' | tee "$OUT/summary.txt"
import json, os, sys
out, k6_rc = sys.argv[1], sys.argv[2]
m = json.load(open(os.path.join(out, "k6-summary.json")))["metrics"]
d = m["http_req_duration"]
def val(name, key):
    return m.get(name, {}).get(key, 0)
snap, section = {}, None
for line in open(os.path.join(out, "env.txt")):
    line = line.strip()
    if line.startswith("--- "):
        section = snap.setdefault(line[4:].split(" ")[0], {}); continue
    if section is not None and "\t" in line:
        k, v = line.split("\t", 1); section[k] = int(v)
restarted, before, after = snap["restarted"], snap["before"], snap["after"]
warm_waits = before["row_lock_waits"] - restarted["row_lock_waits"]
warm_ms = before["row_lock_time_ms"] - restarted["row_lock_time_ms"]
waits = after["row_lock_waits"] - before["row_lock_waits"]
lock_ms = after["row_lock_time_ms"] - before["row_lock_time_ms"]
print(f"label={os.path.basename(out)} k6_exit={k6_rc}")
print(f"http_req_duration avg={d['avg']:.2f} med={d['med']:.2f} p90={d['p(90)']:.2f} "
      f"p95={d['p(95)']:.2f} p99={d['p(99)']:.2f} max={d['max']:.2f} (ms)")
print(f"iterations={val('iterations','count')} dropped={val('dropped_iterations','count')} "
      f"checks={val('checks','value'):.4f} http_req_failed={val('http_req_failed','value'):.4f}")
print(f"orders +{after['orders'] - before['orders']}  outbox_pending after={after['outbox_pending']}  "
      f"outbox_sent before={before['outbox_sent']}")
print(f"innodb row lock waits (측정 구간)   +{waits}  lock time +{lock_ms} ms  avg {lock_ms / waits if waits else 0:.1f} ms/wait")
print(f"innodb row lock waits (워밍업 구간) +{warm_waits}  lock time +{warm_ms} ms")
PY
exit "$k6_rc"
