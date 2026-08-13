#!/usr/bin/env bash
# 부하 한 번 + 수집기 전부를 **한 타임라인**으로 돌리고 결과를 한 폴더에 남긴다.
#
#   ./scripts/perf/run-session.sh fanout                    # 종단 지연 + 팬아웃
#   ./scripts/perf/run-session.sh soak baseline             # 라벨을 붙여 비교용으로
#   RATE=60 DURATION=60s ./scripts/perf/run-session.sh soak relay-on
#   RESET=1 ./scripts/perf/run-session.sh soak relay-off    # 같은 초기 상태에서 시작
#
# ── 왜 러너가 필요한가 ───────────────────────────────────────────────
#
# 지금까지 절차는 수집기를 `&` 로 띄우고 k6 를 돌린 뒤 `kill %1` 하는 것이었다.
# 수집기가 셋으로 늘면(생산자·컨슈머·랙) 그 손절차가 **측정을 망치는 자리**가 된다 —
# 하나를 늦게 켜면 그 지표만 다른 구간을 보고, 그러면 나란히 놓을 수 없다.
# 비교의 전제가 "같은 조건"인데 수집 구간부터 다르면 시작부터 어긋난다.
#
# 그리고 무엇보다 **환경을 같이 남긴다.** performance.md 10장의 결론이
# "측정 환경이 변하면 그 전후 숫자는 비교가 아니라 무관한 값 두 개다" 였는데,
# 그때 환경은 사람이 기억해서 문서에 적는 것이었다. 기억은 회차를 못 버틴다.
#
# ── 남는 것 ──────────────────────────────────────────────────────────
#
#   env.txt              호스트·컨테이너·이미지·릴레이 설정. **이게 없으면 나머지는 숫자일 뿐이다**
#   k6-summary.json      k6 원본 요약
#   k6.log               k6 콘솔 출력
#   outbox.csv           보내는 쪽 (collect-outbox.sh)
#   consumer.csv         받는 쪽 리스너 (collect-consumer.sh)
#   lag.csv              브로커 기준 랙 (collect-lag.sh)
#   stats.csv            컨테이너 CPU·메모리
#   summary.txt          아래 요약을 그대로
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"

SCENARIO="${1:-}"
LABEL="${2:-}"

case "$SCENARIO" in
    smoke)      K6_FILE=smoke.js            ;;
    throughput) K6_FILE=order-throughput.js ;;
    soak)       K6_FILE=order-soak.js       ;;
    fanout)     K6_FILE=payment-callback.js ;;
    *)
        cat >&2 <<'EOF'
사용법: ./scripts/perf/run-session.sh <시나리오> [라벨]

  smoke        1 VU 20초        경로 생존 확인. 본 측정 전에 먼저 돈다
  throughput   20→400 RPS 계단식 한계선 파악 **전용**. 포화로 들어가므로 비교에 쓰지 않는다
  soak         RATE RPS DURATION 동안  구성 간 비교. 반드시 포화 이전 구간에서
  fanout       5 VU 2분         종단 지연 + 컨슈머 팬아웃

환경 변수
  RATE / DURATION      soak 의 부하 (기본 100 / 5m)
  RESET=1              측정 전 orders·outbox_event 를 비우고 order 를 재기동
  INTERVAL             수집 주기 초 (기본 1)
  ORDER_URL 등         주소 재정의. 기본은 호스트 포트가 열린 로컬 스택
EOF
        exit 1
        ;;
esac

INTERVAL="${INTERVAL:-1}"
NET="${PERF_NETWORK:-stove_default}"
STAMP="$(date +%Y%m%d-%H%M%S)"
# **리포 안의 상대경로여야 한다.** k6 는 컨테이너로 돌고 리포를 `/w` 에 마운트하므로
# 요약 파일 경로를 `/w/$OUT_DIR` 로 넘긴다 — 절대경로를 주면 `/w//abs/...` 가 되어
# **k6 요약만 조용히 사라지고 CSV 는 정상으로 쌓인다.** 부분적으로만 실패해서 알아채기 어렵다.
case "${OUT_DIR:-perf-results}" in
    /*) echo "OUT_DIR 는 리포 기준 상대경로여야 한다 (k6 가 컨테이너에서 쓴다): ${OUT_DIR}" >&2; exit 1 ;;
esac
OUT_DIR="${OUT_DIR:-perf-results}/${STAMP}-${SCENARIO}${LABEL:+-$LABEL}"
mkdir -p "$OUT_DIR"

# 주소. 기본은 **호스트 포트가 열린 로컬 스택**이다.
# 원격(ci 오버라이드)은 호스트 포트를 열지 않으므로 e2e 오버라이드의 루프백 1808X 를 준다:
#   ORDER_ACTUATOR=http://127.0.0.1:18082/actuator/prometheus \
#   CONSUMERS="payment=http://127.0.0.1:18083 ..." ./scripts/perf/run-session.sh ...
export ORDER_ACTUATOR="${ORDER_ACTUATOR:-http://localhost:8082/actuator/prometheus}"
export CONSUMERS="${CONSUMERS:-payment=http://localhost:8083 order=http://localhost:8082 license=http://localhost:8084 settlement=http://localhost:8089 download=http://localhost:8088}"
export INTERVAL

# k6 는 컨테이너로 돌리므로 **컨테이너 안에서 보이는 이름**을 준다.
# 수집기(호스트 bash)와 주소 체계가 다른 것이 헷갈리는 지점인데, 둘은 붙는 위치가 다르다.
K6_ORDER_URL="${K6_ORDER_URL:-http://order:8082}"
K6_CATALOG_URL="${K6_CATALOG_URL:-http://catalog:8081}"
K6_PAYMENT_URL="${K6_PAYMENT_URL:-http://payment:8083}"
K6_LICENSE_URL="${K6_LICENSE_URL:-http://license:8084}"

say() { printf '\033[36m▸\033[0m %s\n' "$*"; }

# ── 측정 위생: 같은 초기 상태 ─────────────────────────────────────────
#
# 옵트인이다. 기본으로 지우지 않는 이유는 이 스크립트가 사람이 쓰던 DB 를 말없이 비우면 안 되기 때문이고,
# 대신 **시작 상태를 항상 기록**한다 — 깨끗하지 않은 상태에서 시작한 회차는 나중에 알아볼 수 있어야 한다.
if [ "${RESET:-0}" = "1" ]; then
    say "초기화 — orders · outbox_event 비우고 order 재기동"
    docker compose exec -T mysql mysql -ustove -pstove1234 -e \
        "TRUNCATE stove_order.outbox_event; DELETE FROM stove_order.order_item; DELETE FROM stove_order.orders;" \
        || { echo "초기화 실패 — 중단한다. 더러운 상태로 잰 숫자는 쓸 수 없다" >&2; exit 1; }
    docker compose -f docker-compose.apps.yml restart order >/dev/null
    # 재기동 직후의 첫 스크레이프는 워밍업 전이라 의미가 없다. healthy 를 기다린다.
    for _ in $(seq 1 60); do
        curl -sf --max-time 2 "${ORDER_ACTUATOR%/prometheus}/health" 2>/dev/null | grep -q UP && break
        sleep 2
    done
fi

# ── 환경 기록 ────────────────────────────────────────────────────────
#
# **세 조건이 조용히 같은 값으로 돌면 비교가 통째로 거짓이 된다**(perf-tuning.md 9절).
# 그래서 릴레이 설정을 앱에게 직접 물어 남긴다 — compose 에 적은 값이 아니라 실제로 바인딩된 값이다.
{
    echo "# 측정 세션 ${STAMP}  시나리오=${SCENARIO} 라벨=${LABEL:-없음}"
    echo
    echo "## 호스트"
    uname -srm
    if [ "$(uname -s)" = "Darwin" ]; then
        sysctl -n machdep.cpu.brand_string 2>/dev/null
        sysctl -n hw.memsize | awk '{printf "RAM %.1f GB\n", $1/1024/1024/1024}'
        echo "load: $(sysctl -n vm.loadavg)"
    else
        grep -m1 'model name' /proc/cpuinfo 2>/dev/null
        free -h 2>/dev/null | head -2
        echo "load: $(cat /proc/loadavg)"
    fi
    echo
    echo "## Docker"
    docker info --format 'CPU={{.NCPU}} MEM={{.MemTotal}} ver={{.ServerVersion}}' 2>/dev/null
    echo
    echo "## 컨테이너"
    docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' 2>/dev/null | sort
    echo
    echo "## 조건 오버라이드 (컨테이너에 실제로 걸린 값)"
    # **세 조건이 조용히 같은 값으로 돌면 비교가 통째로 거짓이 된다.** 앱을 가리지 않고 전부 훑는다 —
    # 릴레이 노브는 order 에, 컨슈머 노브는 payment 에 걸리므로 한 컨테이너만 보면 놓친다.
    found=0
    for c in $(docker ps --format '{{.Names}}' 2>/dev/null | grep '^stove-apps-'); do
        env_line=$(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
            | grep -E '^STOVE_OUTBOX|^SPRING_KAFKA_LISTENER|^SPRING_APPLICATION_JSON')
        if [ -n "$env_line" ]; then
            found=1
            echo "$env_line" | sed "s/^/  ${c#stove-apps-}: /"
        fi
    done
    [ "$found" = "0" ] && echo "  (오버라이드 없음 — 전부 코드 기본값)"
    echo
    echo "## 시작 상태"
    docker compose exec -T mysql mysql -ustove -pstove1234 -N -e \
        "SELECT CONCAT('orders=', (SELECT COUNT(*) FROM stove_order.orders),
                       ' outbox_pending=', (SELECT COUNT(*) FROM stove_order.outbox_event WHERE status='PENDING'));" 2>/dev/null \
        || echo "(mysql 조회 실패)"
    # **랙도 시작 상태다.** RESET 은 DB 만 비우므로 앞 회차가 남긴 미소비 메시지는 그대로 남는다.
    # 그 상태로 다음 조건을 재면 "동일한 초기 상태" 가 아니고, 그런데 DB 만 보면 깨끗해 보인다.
    docker exec "${KAFKA_CONTAINER:-stove-kafka}" /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server "${KAFKA_BOOTSTRAP:-kafka:19092}" --describe --all-groups 2>/dev/null \
        | awk '$3 ~ /^[0-9]+$/ && $4 != "-" { lag[$1] += $5 - $4 }
               END { for (g in lag) if (lag[g] > 0) printf "시작 랙 %s=%d\n", g, lag[g]
                     print "(위에 없는 그룹은 랙 0)" }' \
        || echo "(브로커 조회 실패)"
    echo "RESET=${RESET:-0}"
} > "$OUT_DIR/env.txt" 2>&1

say "결과 폴더 → $OUT_DIR"

# ── 수집기 기동 ──────────────────────────────────────────────────────
#
# k6 보다 **먼저** 켜고 **나중에** 끈다. 부하 전후의 기울기가 판정에 쓰이기 때문이다 —
# 부하가 멈춘 뒤 적체가 비워지는 속도가 곧 배수 능력이다.
pids=()
cleanup() {
    for pid in "${pids[@]:-}"; do
        [ -n "$pid" ] && kill "$pid" 2>/dev/null
    done
    wait 2>/dev/null
}
trap cleanup EXIT INT TERM

"$REPO/scripts/perf/collect-outbox.sh"   "$OUT_DIR/outbox.csv"   2>>"$OUT_DIR/collectors.log" & pids+=($!)
"$REPO/scripts/perf/collect-consumer.sh" "$OUT_DIR/consumer.csv" 2>>"$OUT_DIR/collectors.log" & pids+=($!)
"$REPO/scripts/perf/collect-lag.sh"      "$OUT_DIR/lag.csv"      2>>"$OUT_DIR/collectors.log" & pids+=($!)

# 컨테이너 자원. perf-tuning.md 3절이 "대가는 mysql CPU +1.5%p" 라고 적을 수 있었던 근거인데,
# 그때는 사람이 `docker stats` 를 눈으로 봤다. 회차마다 남으면 그게 표가 된다.
(
    echo "elapsed_s,name,cpu_pct,mem_usage"
    started=$(date +%s)
    while true; do
        elapsed=$(( $(date +%s) - started ))
        docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null \
            | sed "s/^/${elapsed},/" | tr -d '%'
        sleep "$INTERVAL"
    done
) > "$OUT_DIR/stats.csv" 2>>"$OUT_DIR/collectors.log" & pids+=($!)

# 수집기가 첫 표본을 잡을 시간. 증분 계산에는 직전 표본이 필요하므로 최소 2주기.
sleep $(( INTERVAL * 2 ))

# ── 부하 ─────────────────────────────────────────────────────────────
say "k6 ${K6_FILE} 시작"
docker run --rm --network "$NET" -v "$REPO:/w" -w /w \
    -e "ORDER_URL=$K6_ORDER_URL" -e "CATALOG_URL=$K6_CATALOG_URL" \
    -e "PAYMENT_URL=$K6_PAYMENT_URL" -e "LICENSE_URL=$K6_LICENSE_URL" \
    -e "RATE=${RATE:-100}" -e "DURATION=${DURATION:-5m}" \
    grafana/k6 run --summary-export="/w/$OUT_DIR/k6-summary.json" \
    "scripts/perf/$K6_FILE" 2>&1 | tee "$OUT_DIR/k6.log"
k6_status=${PIPESTATUS[0]}

# 부하가 끝난 뒤에도 잠시 더 본다. **적체 해소는 부하가 멈춘 다음에 일어난다** —
# 여기서 끊으면 "얼마나 밀렸나"는 알아도 "따라잡는가"는 못 본다.
say "잔여 관측 ${DRAIN_WATCH:-15}초"
sleep "${DRAIN_WATCH:-15}"

cleanup
trap - EXIT INT TERM

# ── 요약 ─────────────────────────────────────────────────────────────
#
# CSV 를 열어 보기 전에 답이 나와야 하는 질문만 추린다.
# **랙은 최종값이 아니라 최대값을 본다** — 다 비워진 뒤의 0 은 "밀린 적 없다"와 구분되지 않는다.
python3 - "$OUT_DIR" <<'PY' | tee "$OUT_DIR/summary.txt"
import csv, json, os, sys

d = sys.argv[1]
out = []

def rows(name):
    p = os.path.join(d, name)
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return list(csv.DictReader(f))

def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None

# k6
p = os.path.join(d, "k6-summary.json")
if os.path.exists(p):
    m = json.load(open(p)).get("metrics", {})
    out.append("## 부하 (k6)")
    reqs = m.get("http_reqs", {})
    dur = m.get("http_req_duration", {})
    failed = m.get("http_req_failed", {})
    if reqs:
        out.append(f"  요청        {reqs.get('count', 0):.0f} 건, {reqs.get('rate', 0):.2f} req/s")
    if dur:
        out.append(f"  응답시간    avg {dur.get('avg', 0):.1f}ms  p90 {dur.get('p(90)', 0):.1f}ms  "
                   f"p95 {dur.get('p(95)', 0):.1f}ms  max {dur.get('max', 0):.1f}ms")
    if failed:
        out.append(f"  실패율      {failed.get('value', 0) * 100:.2f}%")
    e2e = m.get("e2e_fulfillment_latency")
    ok = m.get("e2e_fulfillment_ok")
    if e2e:
        # 완료 건수는 Trend 에서 못 꺼낸다 — k6 는 summary-export 에 Trend 의 count 를 싣지 않는다.
        # 같은 수를 세는 것이 Rate 의 passes 다. 이 값이 지연 분포만큼 중요하다:
        # 같은 5 VU · 2분에 몇 건이 끝났는가가 폴링 주기 개선을 드러낸 지표였다(perf-tuning.md 3절).
        done = (ok or {}).get("passes", 0)
        out.append(f"  종단 지연   avg {e2e.get('avg', 0):.0f}ms  med {e2e.get('med', 0):.0f}ms  "
                   f"p95 {e2e.get('p(95)', 0):.0f}ms  max {e2e.get('max', 0):.0f}ms  "
                   f"(완료 {done:.0f}건)")
    if ok:
        out.append(f"  지급 성공률 {ok.get('value', 0) * 100:.2f}%  "
                   f"({ok.get('passes', 0):.0f}/{ok.get('passes', 0) + ok.get('fails', 0):.0f})")

    # 엔드포인트별. 총계는 폴링 건수에 눌려 쓰기 경로를 감춘다.
    for key, name in (("order_create_latency", "주문 생성"),
                      ("payment_callback_latency", "결제 승인")):
        t = m.get(key)
        if t:
            out.append(f"  {name:<9} avg {t.get('avg', 0):.1f}ms  p90 {t.get('p(90)', 0):.1f}ms  "
                       f"p95 {t.get('p(95)', 0):.1f}ms  max {t.get('max', 0):.1f}ms")

    # 임계값 판정. **여기가 이 세션의 합격/불합격이다.**
    # k6 종료 코드(99)로도 나오지만 요약만 보고 넘어가는 경우가 있어 문장으로 세운다.
    # summary-export 의 값은 "넘었나" 라서 true 가 실패다.
    crossed = [(name, cond)
               for name, mv in m.items() if isinstance(mv, dict)
               for cond, bad in (mv.get("thresholds") or {}).items() if bad]
    if crossed:
        out.append("  임계값      ✗ " + ", ".join(f"{n} {c}" for n, c in crossed))
    else:
        out.append("  임계값      ✓ 전부 통과")
    out.append("")

# outbox — 보내는 쪽
r = rows("outbox.csv")
if r:
    pend = [num(x["pending"]) for x in r if num(x["pending"]) is not None]
    rate = [num(x["published_per_s"]) for x in r if num(x["published_per_s"]) is not None]
    out.append("## 보내는 쪽 (Outbox 릴레이)")
    out.append(f"  발행        총 {float(r[-1]['published_total']) - float(r[0]['published_total']):.0f} 건, "
               f"최대 {max(rate) if rate else 0:.1f} events/s")
    out.append(f"  적체        최대 {max(pend) if pend else 0:.0f} / 최종 {pend[-1] if pend else 0:.0f}")
    out.append(f"  실패·포기   failed {r[-1]['failed_total']}  dead {r[-1]['dead_total']}")
    out.append("")

# lag — 브로커 기준. 이 세션에서 실제로 밀렸는지는 여기서만 알 수 있다.
r = rows("lag.csv")
if r:
    out.append("## 받는 쪽 — 랙 (브로커 커밋 오프셋 기준)")
    keys = sorted({(x["group"], x["topic"]) for x in r})
    for g, t in keys:
        sel = [x for x in r if x["group"] == g and x["topic"] == t]
        lags = [num(x["lag"]) for x in sel if num(x["lag"]) is not None]
        cons = [num(x["consumed_per_s"]) for x in sel if num(x["consumed_per_s"]) is not None]
        # **마지막 표본**의 값을 본다. 최대값으로 보면 스택을 막 띄운 직후의 "아직 커밋 없음"이
        # 세션 내내 경고로 남아, 정말 못 읽고 있는 그룹과 구분되지 않는다.
        unknown = num(sel[-1]["unknown_partitions"]) or 0
        moved = float(sel[-1]["current_offset"]) - float(sel[0]["current_offset"])
        if not lags:
            continue
        note = f"  ⚠ 커밋 없는 파티션 {unknown:.0f}" if unknown else ""
        out.append(f"  {g:<11} {t:<18} 랙 최대 {max(lags):>7.0f} / 최종 {lags[-1]:>7.0f}   "
                   f"소비 {moved:>6.0f}건, 최대 {max(cons) if cons else 0:>6.1f}/s{note}")
    out.append("")

# 리스너 처리시간·예외. 랙이 왜 쌓였는지는 이쪽이 말한다.
r = rows("consumer.csv")
if r:
    out.append("## 받는 쪽 — 리스너")
    for app in sorted({x["app"] for x in r}):
        sel = [x for x in r if x["app"] == app]
        avg = [num(x["avg_handle_ms"]) for x in sel if num(x["avg_handle_ms"]) is not None]
        handled = float(sel[-1]["handled_total"]) - float(sel[0]["handled_total"])
        errs = float(sel[-1]["listener_errors"]) - float(sel[0]["listener_errors"])
        flag = "  ⚠ 예외" if errs else ""
        # 풀은 최대값을 본다. **pending 이 판정값이다** — active 가 최대치라도 pending 이 0 이면
        # 풀은 충분한 것이고, pending 이 서면 그때부터 대기가 응답시간에 그대로 실린다.
        act = [num(x.get("pool_active")) for x in sel if num(x.get("pool_active")) is not None]
        pend = [num(x.get("pool_pending")) for x in sel if num(x.get("pool_pending")) is not None]
        pmax = max((num(x.get("pool_max")) or 0) for x in sel) if sel else 0
        pool = ""
        if pmax:
            warn = "  ⚠ 대기" if pend and max(pend) > 0 else ""
            pool = (f"  풀 active최대 {max(act) if act else 0:.0f}/{pmax:.0f}"
                    f"  pending최대 {max(pend) if pend else 0:.0f}{warn}")
        out.append(f"  {app:<11} 처리 {handled:>6.0f}건  평균 {avg[-1] if avg else 0:>6.2f}ms  "
                   f"예외 {errs:.0f}{flag}{pool}")
    out.append("")

# 자원. 개선의 대가가 여기 찍힌다.
r = rows("stats.csv")
if r:
    peak = {}
    for x in r:
        c = num(x["cpu_pct"])
        if c is not None:
            peak[x["name"]] = max(peak.get(x["name"], 0), c)
    top = sorted(peak.items(), key=lambda kv: -kv[1])[:5]
    out.append("## 자원 — CPU 최대 (상위 5)")
    for name, cpu in top:
        out.append(f"  {name:<28} {cpu:6.1f}%")
    out.append("")

print("\n".join(out))
PY

say "완료 — $OUT_DIR"
exit "$k6_status"
