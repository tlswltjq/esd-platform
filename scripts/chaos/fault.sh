#!/usr/bin/env bash
#
# 장애 주입기 — 이름 붙은 장애를 넣고, 뺀다. 그리고 **정말 들어갔는지 센다.**
#
#   scripts/chaos/fault.sh inject license-db-denied
#   scripts/chaos/fault.sh heal   license-db-denied
#   scripts/chaos/fault.sh status
#   scripts/chaos/fault.sh list
#
# ── 왜 주입 확인이 본체인가 ─────────────────────────────────────────
#
# 장애 실험의 실패 모드는 "장애가 안 났다"가 아니라 **"장애가 안 났는데 났다고 믿는다"** 이다.
# 주입에 실패하면 아무 일도 일어나지 않고, 아무 일도 일어나지 않으면 전 지표가 초록이고,
# 초록은 "우리 시스템은 장애를 견딘다"로 읽힌다. 이 저장소는 같은 함정을 두 번 밟았다 —
# D-021(스텁 격리 규칙이 대상 0건을 검사), D-023(배포 게이트가 컨테이너 0개에도 통과).
# 그래서 여기서는 주입 직후 **실제로 깨졌는지 프로브로 확인**하고, 안 깨졌으면 종료 코드 2 로 멈춘다.
#
# ── 왜 컨테이너를 끄지 않고 권한을 뺏는가 ───────────────────────────
#
# 서비스 7종이 MySQL 컨테이너 **하나**를 공유한다(스키마만 다르다). `docker stop stove-mysql` 은
# license 뿐 아니라 order·payment 까지 같이 죽여서, 정작 **장애 중에 결제를 흘리는 것이 불가능**해진다.
# 재려는 것은 "license 의 DB 가 죽었을 때 결제 경로가 어떻게 행동하는가"이므로 장애는
# license 에만 닿아야 한다.
#
# MySQL 권한은 스키마·테이블 단위라 정확히 그 경계를 그어 준다. 되돌리는 것도 GRANT 한 줄이다.
# 현실의 대응물도 흔하다 — 레플리카로 페일오버했는데 그쪽 계정에 쓰기 권한이 없는 경우,
# 권한 변경 배포가 잘못 나간 경우.
#
# **KILL 이 왜 같이 필요한가** — MySQL 의 데이터베이스 단위 권한 변경은 문서상
# "클라이언트가 다음에 USE 를 실행할 때" 반영된다. HikariCP 는 커넥션을 붙들고 있으므로
# REVOKE 만으로는 기존 커넥션 20개가 멀쩡히 돈다. 끊어서 재접속시켜야 권한이 다시 평가된다.
# (이걸 모르고 REVOKE 만 걸었다가 프로브가 200 을 내는 걸 보고 알았다. 프로브가 없었으면
#  "장애를 견뎠다"고 적을 뻔했다.)
set -uo pipefail

MYSQL_CONTAINER=${MYSQL_CONTAINER:-stove-mysql}
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-root1234}
APP_USER=${APP_USER:-stove}
LICENSE_URL=${LICENSE_URL:-http://localhost:8084}
DOWNLOAD_URL=${DOWNLOAD_URL:-http://localhost:8088}

# license 스키마에서 '라이선스 원장' 이 아닌 테이블들. 테이블 단위 장애를 만들 때 살려 두는 쪽이다.
KEEP_TABLES=(outbox_event processed_event flyway_schema_history)

sql() {
    docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B 2>/dev/null
}

sql_e() {
    docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "$1" 2>/dev/null
}

# license 의 커넥션을 전부 끊는다. 권한 재평가를 강제하는 것이 목적이다.
kill_license_connections() {
    sql_e "select concat('KILL ',id,';') from information_schema.processlist where db='stove_license';" | sql
}

# 프로브: license 가 자기 원장을 읽을 수 있는가. 200 이면 살아 있고 500 이면 끊겨 있다.
probe_license() {
    curl -s -o /dev/null -w '%{http_code}' -m 5 "$LICENSE_URL/api/v1/library" -H 'X-Member-Id: 1' 2>/dev/null
}

# 대조 프로브: 장애가 다른 서비스로 번지지 않았는가. 번졌으면 그 회차의 숫자는 못 쓴다.
probe_order() {
    curl -s -o /dev/null -w '%{http_code}' -m 5 -X POST "${ORDER_URL:-http://localhost:8082}/api/v1/orders" \
        -H 'Content-Type: application/json' \
        -d '{"memberId":999999,"items":[{"productId":1,"quantity":1}]}' 2>/dev/null
}

# 커넥션이 새로 서고 권한이 반영될 때까지 기다린다. 고정 sleep 은 짧으면 거짓 판정이고 길면 낭비다.
await_probe() {
    local want=$1 label=$2 deadline=$((SECONDS + 30)) got
    while [ $SECONDS -lt $deadline ]; do
        got=$(probe_license)
        [ "$got" = "$want" ] && { echo "  프로브 $label: HTTP $got"; return 0; }
        sleep 1
    done
    echo "  프로브 $label: HTTP $got (기대 $want)" >&2
    return 1
}

# ── 장애 정의 ───────────────────────────────────────────────────────

# license 스키마 전체가 닿지 않는다. 인스턴스/스키마 단위 장애의 모형.
#   → issue() 도 recordIssueFailure() 도 전부 실패한다.
inject_license_db_denied() {
    sql_e "REVOKE ALL PRIVILEGES ON \`stove_license\`.* FROM '$APP_USER'@'%'; FLUSH PRIVILEGES;"
    kill_license_connections
}

heal_license_db_denied() {
    sql_e "GRANT ALL PRIVILEGES ON \`stove_license\`.* TO '$APP_USER'@'%'; FLUSH PRIVILEGES;"
    kill_license_connections
}

# 라이선스 원장 테이블만 닿지 않는다. 테이블 단위 장애의 모형 —
# 잠금 경합, 끝나지 않은 DDL, 테이블스페이스 손상, 부분적인 권한 사고가 여기에 해당한다.
#   → issue() 는 실패하고 recordIssueFailure() 는 **성공한다.** 이 차이가 이 실험의 전부다.
inject_license_table_denied() {
    local grants=""
    for t in "${KEEP_TABLES[@]}"; do
        grants+="GRANT ALL PRIVILEGES ON \`stove_license\`.\`$t\` TO '$APP_USER'@'%'; "
    done
    sql_e "REVOKE ALL PRIVILEGES ON \`stove_license\`.* FROM '$APP_USER'@'%'; $grants FLUSH PRIVILEGES;"
    kill_license_connections
}

heal_license_table_denied() {
    local revokes=""
    for t in "${KEEP_TABLES[@]}"; do
        revokes+="REVOKE ALL PRIVILEGES ON \`stove_license\`.\`$t\` FROM '$APP_USER'@'%'; "
    done
    sql_e "GRANT ALL PRIVILEGES ON \`stove_license\`.* TO '$APP_USER'@'%'; $revokes FLUSH PRIVILEGES;"
    kill_license_connections
}

# license 프로세스 자체가 없다. 다운로드가 정말 독립인지 보는 대조군용.
inject_license_stopped() { docker stop stove-apps-license-1 >/dev/null; }
heal_license_stopped()   { docker start stove-apps-license-1 >/dev/null; }

# ── 명령 ────────────────────────────────────────────────────────────

usage() {
    cat <<'EOF'
scripts/chaos/fault.sh <명령> [장애이름]

명령
  list            장애 목록
  status          지금 무엇이 주입되어 있는가 (권한 상태 + 프로브)
  inject <이름>   주입하고, 실제로 깨졌는지 확인한다 (확인 실패 시 종료 코드 2)
  heal   <이름>   복구하고, 실제로 돌아왔는지 확인한다

장애
  license-db-denied      license 스키마 전체 접근 불가 (스키마 단위 장애)
  license-table-denied   license 원장 테이블만 접근 불가 (테이블 단위 장애)
  license-stopped        license 컨테이너 정지 (서비스 단위 장애)
EOF
}

cmd_status() {
    echo "── 권한 ──"
    sql_e "show grants for '$APP_USER'@'%';" | grep -i stove_license || echo "  (license 권한 없음)"
    echo "── 프로브 ──"
    echo "  license  HTTP $(probe_license)   (200=정상, 500=DB 접근 불가, 000=프로세스 없음)"
    echo "  download HTTP $(curl -s -o /dev/null -w '%{http_code}' -m 5 "$DOWNLOAD_URL/actuator/health" 2>/dev/null)"
}

main() {
    local cmd=${1:-} name=${2:-}
    case "$cmd" in
        list) usage; return 0 ;;
        status) cmd_status; return 0 ;;
        inject|heal) : ;;
        *) usage; return 1 ;;
    esac

    case "$name" in
        license-db-denied|license-table-denied)
            if [ "$cmd" = inject ]; then
                echo "장애 주입: $name"
                "inject_${name//-/_}"
                await_probe 500 "장애 확인" || {
                    echo "주입이 반영되지 않았다 — 이 상태로 재면 '견뎠다'는 거짓 결론이 나온다." >&2
                    return 2
                }
                local other; other=$(probe_order)
                [ "$other" = 200 ] || {
                    echo "  ✗ 장애가 order 로 번졌다 (HTTP $other). 격리되지 않은 회차는 못 쓴다." >&2
                    return 2
                }
                echo "  대조 프로브: order HTTP $other (격리 확인)"
            else
                echo "복구: $name"
                "heal_${name//-/_}"
                await_probe 200 "복구 확인" || return 2
            fi
            ;;
        license-stopped)
            if [ "$cmd" = inject ]; then
                echo "장애 주입: $name"; inject_license_stopped
                local deadline=$((SECONDS + 30))
                while [ $SECONDS -lt $deadline ]; do
                    [ "$(probe_license)" = "000" ] && { echo "  프로브 장애 확인: 응답 없음"; return 0; }
                    sleep 1
                done
                echo "  license 가 아직 응답한다 — 주입 실패" >&2; return 2
            else
                echo "복구: $name"; heal_license_stopped
                await_probe 200 "복구 확인" || return 2
            fi
            ;;
        *) echo "그런 장애가 없다: $name" >&2; usage; return 1 ;;
    esac
}

main "$@"
