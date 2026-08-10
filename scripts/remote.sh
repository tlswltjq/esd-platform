#!/usr/bin/env bash
#
# 인프라가 필요한 작업을 원격(OCI)에서 돌린다 — 커밋하지 않고.
#
#   ./scripts/remote.sh test                     전체 테스트
#   ./scripts/remote.sh test :apps:order         모듈 하나
#   ./scripts/remote.sh stack up                 전체 스택 20개 (게이트까지 확인한다)
#   ./scripts/remote.sh smoke                    인수 시나리오 관통 확인
#
# **왜 있는가** — CI(경로 D)는 push 해야 돈다. "고쳤다 → 결과" 루프에는 커밋이 끼면 안 된다.
# 이 스크립트는 그 사이를 메운다. rsync 로 작업본을 밀어넣고 원격에서 실행한다.
#
# **요구 도구 0개** — bash·ssh·rsync 는 macOS 기본 탑재다(decisions.md 12번).
#
# **머신별 값은 리포에 적지 않는다**(10번). ssh 별칭과 원격 경로는 `git config` 에 둔다 —
# `.git/config` 는 커밋되지 않고, git 은 이미 있는 도구라 설치 요구가 늘지 않는다.
#
#   git config stove.remote    <ssh-별칭>        # 예: sng
#   git config stove.remotedir /opt/stove-stack  # 생략 시 기본값
#
# 환경변수 STOVE_REMOTE / STOVE_REMOTE_DIR 가 있으면 그쪽이 우선한다.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

REMOTE="${STOVE_REMOTE:-$(git config --get stove.remote 2>/dev/null || true)}"
RDIR="${STOVE_REMOTE_DIR:-$(git config --get stove.remotedir 2>/dev/null || echo /opt/stove-stack)}"

if [ -z "$REMOTE" ]; then
    cat >&2 <<'EOF'
원격 호스트가 설정되지 않았습니다.

  git config stove.remote <ssh-별칭>

ssh 별칭은 ~/.ssh/config 에 둡니다 (리포 밖 — 머신별 값이므로):

  Host stove-oci
      HostName <instance-ip>
      User ubuntu
      IdentityFile ~/.ssh/<key>
EOF
    exit 1
fi

NET=stove_default
INFRA=(-f docker-compose.yml      -f docker-compose.ci.yml)
APPS=( -f docker-compose.apps.yml -f docker-compose.apps.ci.yml)

say()  { printf '\033[36m▸\033[0m %s\n' "$*"; }
die()  { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# 원격에서 명령을 돈다. 항상 원격 작업 디렉터리에서 시작한다.
rexec() { ssh -o ConnectTimeout=10 "$REMOTE" "cd '$RDIR' && $*"; }

# 컨테이너 네트워크 안에서 curl 을 돈다. 호스트 포트를 열지 않기 때문이다
# (docker-compose.ci.yml 의 주석 참고).
rcurl() { ssh -o ConnectTimeout=10 "$REMOTE" \
    "docker run --rm --network $NET curlimages/curl:latest -s $*"; }

# ── sync ──────────────────────────────────────────────────────────────
# 로컬 → 원격 단방향. 원격에서 소스를 고치지 않는다(다음 sync 에 지워진다).
# build/ 와 .gradle/ 은 제외한다 — 제외한 경로는 --delete 대상도 아니므로
# 원격의 산출물과 캐시가 살아남는다. 그것이 두 번째 실행이 빠른 이유다.
do_sync() {
    command -v rsync >/dev/null || die "rsync 를 찾을 수 없습니다"
    say "sync → $REMOTE:$RDIR"
    rsync -az --delete \
        --exclude '.git/' --exclude 'build/' --exclude '.gradle/' \
        --exclude '.idea/' --exclude '.claude/' --exclude 'perf-results/' \
        --exclude '*.log' \
        "$REPO/" "$REMOTE:$RDIR/" \
        || die "rsync 실패"
}

# ── test ──────────────────────────────────────────────────────────────
# 실패했을 때 로그 전문 대신 JUnit XML 을 읽어 요약한다.
# 전문은 원격에 남고 경로만 알려준다.
summarize_failures() {
    ssh -o ConnectTimeout=10 "$REMOTE" bash -s <<EOF
cd '$RDIR' || exit 0
found=0
for f in {apps,common}/*/build/test-results/test/TEST-*.xml; do
  [ -f "\$f" ] || continue
  fails=\$(grep -o 'failures="[0-9]*"' "\$f" | head -1 | grep -o '[0-9]*')
  errs=\$(grep -o 'errors="[0-9]*"'   "\$f" | head -1 | grep -o '[0-9]*')
  [ "\${fails:-0}\${errs:-0}" = "00" ] && continue
  found=1
  cls=\$(basename "\$f" .xml); cls=\${cls#TEST-}
  printf '  \033[31m✗\033[0m %s\n' "\$cls"
  # 실패한 testcase 이름과 메시지를 짝지어 낸다.
  # XML 엔티티(&#10; &quot; &lt; …)를 사람이 읽는 형태로 되돌린다.
  awk '
    /<testcase / {
      name = \$0
      sub(/.*[ ]name="/, "", name); sub(/".*/, "", name)
    }
    /<failure|<error/ {
      if (\$0 !~ /message="/) next
      msg = \$0
      sub(/.*message="/, "", msg); sub(/".*/, "", msg)
      gsub(/&#10;/, " ", msg); gsub(/&#13;/, "", msg)
      gsub(/&quot;/, "\"", msg); gsub(/&apos;/, "'\''", msg)
      gsub(/&lt;/, "<", msg); gsub(/&gt;/, ">", msg)
      # gsub 의 치환문자열에서 & 는 "매치 전체"를 뜻한다. 리터럴 & 는 \& 로 써야 하고,
      # 이 awk 프로그램이 비인용 heredoc 을 통과하므로 백슬래시가 4개 필요하다.
      gsub(/&amp;/, "\\\\&", msg)
      printf "      %s\n        %.150s\n", name, msg
    }' "\$f" | head -12
done
[ "\$found" = "0" ] && echo "  (JUnit XML 에서 실패를 찾지 못했습니다 — 컴파일 오류일 수 있습니다)"
echo
echo "  전문: $REMOTE:$RDIR/{apps,common}/*/build/reports/tests/test/index.html"
EOF
}

do_test() {
    local target="${1:-}" filter="${2:-}"
    local task="test"
    [ -n "$target" ] && task="$target:test"
    local args=("$task")
    [ -n "$filter" ] && args+=(--tests "$filter")
    do_sync
    say "gradle ${args[*]}"
    if rexec "./gradlew ${args[*]} --console=plain"; then
        printf '\033[32m✓\033[0m 통과\n'
    else
        printf '\033[31m✗\033[0m 실패 — 요약:\n'
        summarize_failures
        return 1
    fi
}

# ── stack ─────────────────────────────────────────────────────────────
#
# 대기와 게이트 판정은 scripts/stack-wait.sh 가 한다. 여기 함수로 있던 대기 루프를
# 그쪽으로 옮긴 이유는 **CI 와 같은 것을 돌리기 위해서**다 — 예전에는 대기가 이 파일에,
# 게이트 판정이 smoke-stack.sh 에 있어서 어느 쪽도 혼자서는 배포 가능 여부를 말하지 못했다.
do_stack() {
    local action="${1:-status}" target="${2:-all}"
    case "$action" in
        up)
            do_sync
            case "$target" in
                infra) say "인프라 10종";       rexec "docker compose ${INFRA[*]} up -d" ;;
                apps)  say "앱 10종";           rexec "docker compose ${APPS[*]} up -d --build" ;;
                all)   say "인프라 + 앱 20종"
                       rexec "docker compose ${INFRA[*]} up -d" || die "인프라 기동 실패"
                       rexec "docker compose ${APPS[*]} up -d --build" ;;
            esac
            # 게이트가 막으면 `stack up` 도 실패다. 뜬 것과 서비스 가능한 것은 다르다.
            rexec "bash scripts/stack-wait.sh" || die "게이트 불통과 — 스택이 서비스 가능한 상태가 아니다"
            ;;
        down)
            # -v 를 쓰지 않는다. 볼륨은 남긴다.
            case "$target" in
                infra) rexec "docker compose ${INFRA[*]} down" ;;
                apps)  rexec "docker compose ${APPS[*]} down" ;;
                all)   rexec "docker compose ${APPS[*]} down; docker compose ${INFRA[*]} down" ;;
            esac
            ;;
        status)
            rexec "docker ps --format 'table {{.Names}}\t{{.Status}}' | sort"
            ;;
        *) die "stack: up|down|status 중 하나 (받은 값: $action)" ;;
    esac
}

# ── 그 외 ─────────────────────────────────────────────────────────────
do_logs() {
    local svc="${1:-}"; shift || true
    [ -n "$svc" ] || die "서비스 이름이 필요합니다 (예: catalog)"
    local n=100 pat=""
    while [ $# -gt 0 ]; do
        case "$1" in
            -n) n="$2"; shift 2 ;;
            -g) pat="$2"; shift 2 ;;
            *)  shift ;;
        esac
    done
    # 컨테이너 이름은 프로젝트에 따라 stove-<svc> 또는 stove-apps-<svc>-1 이다.
    local cmd="docker logs --tail $n \$(docker ps --format '{{.Names}}' | grep -E '(^stove-${svc}\$|^stove-apps-${svc}-)' | head -1) 2>&1"
    [ -n "$pat" ] && cmd="$cmd | grep -i --color=never '$pat'"
    rexec "$cmd"
}

do_http() {
    local method="${1:-GET}" path="${2:-/}" body="${3:-}"
    # 경로 앞에 서비스가 붙는다: remote.sh http GET catalog:8081/api/v1/products
    local url="$path"
    [[ "$url" == http* ]] || url="http://$url"
    local args="-w '\n[HTTP %{http_code}]\n' -X $method '$url'"
    [ -n "$body" ] && args="$args -H 'Content-Type: application/json' -d '$body'"
    rcurl "$args"
}

do_status() {
    say "$REMOTE ($RDIR)"
    ssh -o ConnectTimeout=10 "$REMOTE" '
      printf "  컨테이너 %s개 (unhealthy %s)\n" \
        "$(docker ps -q|wc -l|tr -d " ")" "$(docker ps --filter health=unhealthy -q|wc -l|tr -d " ")"
      free -m | awk "/Mem:/{printf \"  메모리 used %d / available %d MiB\n\", \$3, \$7}
                     /Swap:/{printf \"  swap %d / %d MiB\n\", \$3, \$2}"
      df -h / | tail -1 | awk "{printf \"  디스크 %s / %s (%s)\n\", \$3, \$2, \$5}"
      printf "  러너 %s\n" "$(systemctl is-active actions.runner.* 2>/dev/null | head -1)"'
}

usage() {
    cat <<'EOF'
사용법: ./scripts/remote.sh <명령> [인자]

  sync                       로컬 작업본을 원격으로 밀어넣는다
  gradle <인자...>           원격에서 ./gradlew 실행 (sync 포함)
  test [모듈] [필터]         테스트 + 실패 요약   예: test :apps:order '*ServiceTest'
  stack up|down|status [infra|apps|all]
  gate                       배포 게이트만 다시 확인 (stack up 이 이미 부른다)
  smoke                      인수 시나리오 관통 확인 (스택이 떠 있어야 함)
  logs <서비스> [-n N] [-g 패턴]
  http <메서드> <서비스:포트/경로> [본문]
  status                     원격 자원 상태
  shell                      원격 셸

설정(리포 밖):
  git config stove.remote    <ssh-별칭>
  git config stove.remotedir <경로>        기본값 /opt/stove-stack

로컬에서 도는 것은 로컬에서 — 단위 테스트와 ArchUnit 규칙은 Docker 가 필요 없다.
이 스크립트는 **인프라가 필요한 것**을 위한 것이다.
EOF
}

cmd="${1:-}"; shift || true
case "$cmd" in
    sync)   do_sync ;;
    gradle) do_sync; say "gradle $*"; rexec "./gradlew $* --console=plain" ;;
    test)   do_test "${1:-}" "${2:-}" ;;
    stack)  do_stack "${1:-status}" "${2:-all}" ;;
    gate)   do_sync; rexec "bash scripts/stack-wait.sh" ;;
    smoke)  do_sync; rexec "bash scripts/smoke-stack.sh" ;;
    logs)   do_logs "$@" ;;
    http)   do_http "${1:-GET}" "${2:-/}" "${3:-}" ;;
    status) do_status ;;
    shell)  ssh -t "$REMOTE" "cd '$RDIR' && exec \$SHELL -l" ;;
    ""|-h|--help|help) usage ;;
    *)      die "알 수 없는 명령: $cmd  (--help 참고)" ;;
esac
