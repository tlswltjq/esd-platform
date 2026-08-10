#!/usr/bin/env bash
#
# 스택이 서비스 가능한 상태인지 확인하고, 아니면 세운다.
#
#   bash scripts/stack-wait.sh
#
# 사전 조건은 compose 가 이미 up 되어 있는 것뿐이다. **기다리는 것도 이 스크립트가 한다.**
#
# **왜 한 파일인가** — 예전에는 대기(`remote.sh` 의 `stack_wait`)와 판정(`smoke-stack.sh` 의
# 0·4·5·6장)이 갈라져 있었다. 둘은 같은 자리에 있어야 한다. 기다린 뒤에 아무도 확인하지 않으면
# 기다린 의미가 없고, 확인만 하고 기다리지 않으면 기동 중인 스택을 고장으로 읽는다.
#
# **인수 시나리오(트랙 A~C)는 여기 없다.** 그쪽은 `:e2e` 모듈이 한다 — 필요 조건이 다르다.
# 게이트는 데이터가 필요 없고 수 초에 끝나며 **배포의 일부**다. 인수는 데이터를 만들고
# 전파를 기다리며 main push 에 붙는다(docs/test-audit.md 4.1).
#
# 호스트 포트를 쓰지 않는다. CI 호스트는 공개 IP 를 가지므로 포트를 열지 않고
# (docker-compose.ci.yml), 같은 네트워크에 컨테이너를 띄워 서비스 이름으로 부른다.
# 그래서 원격에서도 CI 에서도 같은 파일이 그대로 돈다 — 필요한 것은 docker 하나다.
#
# 종료 코드:
#   0  게이트 통과
#   2  게이트 불통과 — 배포를 진행해서는 안 된다
set -uo pipefail

NET=stove_default
RUNNER=(docker run --rm --network "$NET" curlimages/curl:latest -s)

APPS=(gateway:8080 catalog:8081 order:8082 payment:8083 license:8084
      studio:8085 review:8086 store:8087 download:8088 settlement:8089)

# 인프라 10종. 이름이 고정이라 대조할 수 있다(docker-compose.yml 의 container_name).
INFRA=(stove-mysql stove-redis stove-kafka stove-kafka-ui stove-elasticsearch
       stove-minio stove-mongodb stove-prometheus stove-tempo stove-grafana)

# 이 스크립트가 온전히 돌았을 때 내려야 하는 판정의 수.
#
# smoke-stack.sh 에서 가져온 장치다(decisions.md 18번 옆 흐름). 판정 지점을 늘렸으면 이 값도
# 같이 올린다 — 손으로 유지하는 브리틀함이 목적이다. **늘어나는 것은 정상이고 모르게 줄어드는 것이 사고다.**
# 게이트에서는 한 걸음 더 간다: 판정이 모자라면 통과시키지 않는다. 세지 못한 게이트는 게이트가 아니다.
EXPECTED_CHECKS=14

pass=0; fail=0
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  \033[31m✗\033[0m %s — %s\n' "$1" "$2"; fail=$((fail+1)); }

status() { "${RUNNER[@]}" -o /dev/null -w '%{http_code}' "$@" 2>/dev/null; }

# curl 은 응답을 못 받아도 상태코드 자리에 `000` 을 찍는다. 그대로 실으면
# "404 를 기대했는데 000" 처럼 읽혀 **차단이 뚫린 것**과 **부르지도 못한 것**이 한 문장이 된다.
# 게이트에서 둘은 대응이 다르다 — 앞은 라우팅 규칙을, 뒤는 컨테이너를 본다.
describe() { [ "$1" = "000" ] && echo "무응답" || echo "HTTP $1"; }

# ── 대기 ──────────────────────────────────────────────────────────────
# starting 이 사라질 때까지 기다린다. 앱 10종의 HEALTHCHECK 가 start-period 40초라
# 기동 직후에는 전부 starting 이다.
echo "=== 0. healthy 대기 ==="
for i in $(seq 1 40); do
    running=$(docker ps -q | wc -l | tr -d ' ')
    healthy=$(docker ps --filter health=healthy -q | wc -l | tr -d ' ')
    starting=$(docker ps --filter health=starting -q | wc -l | tr -d ' ')
    printf '  %3ds  실행 %s / healthy %s / starting %s\n' $((i * 5)) "$running" "$healthy" "$starting"

    # 컨테이너가 하나도 없으면 기다릴 대상이 없다. 200초를 세는 대신 바로 판정으로 넘어간다 —
    # **"없다"고 말하는 것이 이 스크립트가 하는 일이다.**
    [ "$running" -eq 0 ] && break
    [ "$starting" -eq 0 ] && [ "$i" -gt 2 ] && break
    sleep 5
done

# ── 게이트 ────────────────────────────────────────────────────────────
#
# 액추에이터를 맨 앞에 둔다. 부트의 health 는 DataSource·Redis·MongoDB·Elasticsearch 지표를
# **집계**하므로, 여기가 UP 이면 그 넷은 살아 있다는 뜻이다.
#
# compose 의 healthcheck 미정의 7종을 셋이 나눠 덮는다 — 어느 하나로도 충분하지 않다(D-023).
#
#   redis · mongodb                       이 장이 간접으로 (부트가 집계한다)
#   kafka                                 2장이 직접 찌른다 (부트에 대응 지표가 없다)
#   kafka-ui · prometheus · tempo · grafana   5장의 컨테이너 집합 대조만이 본다
echo
echo "=== 1. 앱 10종 액추에이터 ==="
for svc in "${APPS[@]}"; do
    name=${svc%%:*}
    st=$(status "http://$svc/actuator/health")
    [ "$st" = "200" ] && ok "$name health" || bad "$name health" "$(describe "$st")"
done

echo
echo "=== 2. 브로커 ==="
# 부트에 기본 Kafka 헬스 지표가 없고 compose 에도 healthcheck 가 없다. 그래서
# **브로커가 죽어도 위의 열 줄이 전부 초록이다** — 앱은 뜨고 API 도 응답하며, 이벤트만 멈춘다.
# 여기가 그 자리를 막는다.
if docker exec stove-kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
        --bootstrap-server kafka:19092 >/dev/null 2>&1; then
    ok "kafka 브로커 응답"
else
    bad "kafka 브로커 응답" "kafka:19092 에서 API 버전을 받지 못했다"
fi

echo
echo "=== 3. 인프라 도달성 ==="
st=$(status http://elasticsearch:9200/_cluster/health)
[ "$st" = "200" ] && ok "elasticsearch" || bad "elasticsearch" "$(describe "$st")"

echo
echo "=== 4. 게이트웨이 내부 API 차단 ==="
st=$(status -X POST http://gateway:8080/api/v1/products/quote \
     -H 'Content-Type: application/json' -d '{"items":[]}')
[ "$st" = "404" ] && ok "quote 는 게이트웨이로 못 부른다 (404)" \
                  || bad "quote 는 게이트웨이로 못 부른다" "기대 404, 실제 $(describe "$st")"

echo
echo "=== 5. 컨테이너 집합 ==="
#
# **여기가 D-023 을 막는 자리다.**
#
# 예전 판정은 이랬다:
#
#   unhealthy=$(docker ps --filter health=unhealthy --format '{{.Names}}')
#   [ -n "$unhealthy" ] && bad ... || ok "unhealthy 없음"
#
# 빈 결과를 "정상" 으로 읽는다. 그래서 **컨테이너가 0개여도 통과했다.** 스택이 통째로
# 내려가 있는 것이 이 게이트에서 가장 조용한 상태였다.
#
# 없는 것을 세려면 있어야 할 것을 알아야 한다. 그래서 기대 이름을 적고 대조한다 —
# 그리고 몇 개가 없다가 아니라 **무엇이 없는지를 이름으로 말한다.**
running_names=$(docker ps --format '{{.Names}}')
missing=()
for c in "${INFRA[@]}"; do
    printf '%s\n' "$running_names" | grep -qx "$c" || missing+=("$c")
done
# 앱은 container_name 이 없어 compose 가 stove-apps-<서비스>-1 로 짓는다.
for svc in "${APPS[@]}"; do
    name=${svc%%:*}
    printf '%s\n' "$running_names" | grep -q "^stove-apps-${name}-" || missing+=("$name(앱)")
done
unhealthy=$(docker ps --filter health=unhealthy --format '{{.Names}}' | tr '\n' ' ')

if [ ${#missing[@]} -gt 0 ]; then
    bad "컨테이너 20종" "실행되지 않음: ${missing[*]}"
elif [ -n "$unhealthy" ]; then
    bad "컨테이너 20종" "unhealthy: $unhealthy"
else
    ok "컨테이너 20종 전부 실행 · unhealthy 없음"
fi

echo
echo "════════════════════════════════"
ran=$((pass + fail))
if [ "$ran" -ne "$EXPECTED_CHECKS" ]; then
    printf '  통과 %d / 실패 %d  \033[33m(기대 %d — 판정 수가 다르다)\033[0m\n' \
        "$pass" "$fail" "$EXPECTED_CHECKS"
    echo "════════════════════════════════"
    exit 2
fi
printf '  통과 %d / 실패 %d\n' "$pass" "$fail"
echo "════════════════════════════════"

[ "$fail" -eq 0 ] || exit 2
exit 0
