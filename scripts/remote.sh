#!/usr/bin/env bash
#
# 인프라가 필요한 작업을 원격(OCI)에서 돌린다 — 커밋하지 않고.
#
#   ./scripts/remote.sh test                     전체 테스트
#   ./scripts/remote.sh test :apps:order         모듈 하나
#   ./scripts/remote.sh stack up                 전체 스택 20개 (게이트까지 확인한다)
#   ./scripts/remote.sh e2e                      인수 시나리오 관통 확인
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
#   git config stove.remotedir ~/stove/<이름>    # 생략 시 머신별 기본값
#
# 환경변수 STOVE_REMOTE / STOVE_REMOTE_DIR 가 있으면 그쪽이 우선한다.
#
# **작업 디렉터리는 머신마다 다르다.** 개발 머신이 여럿이고(랩탑·실습실) 원격이 하나이므로,
# 기본값이 고정이면 두 머신이 같은 자리에 rsync 한다 — `--delete` 라서 **뒤에 민 쪽이
# 앞의 작업본을 조용히 지운다.** 그래서 기본값에 `사용자-호스트` 를 박는다.
# 일부러 공유하려면 그때 `git config stove.remotedir` 로 같은 값을 주면 된다.
#
# 기본값은 **원격 홈 기준 상대 경로**다. `/opt` 은 root 소유라 머신을 하나 더 붙일 때마다
# sudo 가 필요하고, 그러면 "새 머신에서 바로 쓴다" 가 성립하지 않는다. 홈 아래는 권한이
# 필요 없고 원격이 리눅스든 맥이든 같은 식으로 잡힌다. 절대 경로를 주면 그대로 쓴다.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

REMOTE="${STOVE_REMOTE:-$(git config --get stove.remote 2>/dev/null || true)}"

# 호스트 이름에서 경로에 쓸 수 없는 문자를 걷어낸다(.local 접미사 포함).
default_rdir() {
    local who host
    who=$(id -un 2>/dev/null || echo user)
    host=$(hostname -s 2>/dev/null || hostname)
    printf 'stove/%s' "$(printf '%s-%s' "$who" "$host" | tr -c 'A-Za-z0-9._-' '-')"
}
RDIR="${STOVE_REMOTE_DIR:-$(git config --get stove.remotedir 2>/dev/null || default_rdir)}"

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
INFRA=(-f docker-compose.yml      -f docker-compose.ci.yml -f docker-compose.e2e.yml)
# 앱 오버라이드가 셋이고 **순서가 의미를 가진다** — ci 가 ports 를 !reset 으로 지운 뒤에
# e2e 가 127.0.0.1:1808X 로 다시 연다. 반대로 놓으면 조용히 닫힌다(decisions.md 21번).
#
# 원격 스택은 항상 루프백 포트를 열어 둔다. `remote.sh e2e` 가 그 포트로 붙고,
# 열려 있어도 호스트 밖에서는 닿지 않으므로 ci 오버라이드의 근거를 건드리지 않는다.
APPS=( -f docker-compose.apps.yml -f docker-compose.apps.ci.yml -f docker-compose.apps.e2e.yml)

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
#
# `runs/` 도 같은 이유로 제외한다. **측정 산출물은 로컬에 없다** — 원격에서만 생기므로
# 제외하지 않으면 `--delete` 가 지우려 든다. 회차 하나가 40분이라 지우면 되돌릴 수 없다.
do_sync() {
    command -v rsync >/dev/null || die "rsync 를 찾을 수 없습니다"
    say "sync → $REMOTE:$RDIR"
    # 머신별 기본 경로라 첫 실행에는 없다. rsync 는 상위 디렉터리를 만들지 않는다.
    ssh -o ConnectTimeout=10 "$REMOTE" "mkdir -p '$RDIR'" || die "원격 디렉터리를 만들 수 없습니다: $RDIR"
    rsync -az --delete \
        --exclude '.git/' --exclude 'build/' --exclude '.gradle/' \
        --exclude '.idea/' --exclude '.claude/' --exclude 'perf-results/' \
        --exclude 'runs/' \
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
# 게이트 판정이 셸 스모크에 있어서 어느 쪽도 혼자서는 배포 가능 여부를 말하지 못했다.
#
# **전체 스택은 원격 한 대에 한 벌이 상한이다.** 인프라 11종이 `container_name:` 으로
# 이름을 고정하고 있어 두 벌이 공존할 수 없다. 그래서 `stack up` 은 남의 것을 갈아엎을 수
# 있는 명령이고, 아래 두 검사가 그 자리를 지킨다.

# 앱 이미지는 미리 만들어진 jar 를 복사할 뿐이다(apps/*/Dockerfile).
# `docker compose --build` 는 `docker build` 만 부르므로 **Gradle 을 돌리지 않는다.**
# do_sync 는 `build/` 를 제외하니(캐시 보존) 원격에는 지난번 jar 가 남아 있고,
# 그것이 그대로 이미지가 된다 — **sync 한 코드가 아닌 것이 조용히 뜬다.**
# 실제로 한 번 밟았다: V7 마이그레이션이 없는 8일 전 jar 가 떠서 스케줄러 검증이 무효였다.
# Flyway 경고가 우연히 잡아 줬을 뿐, 마이그레이션 없는 변경이었으면 못 봤다.
build_jars() {
    say "bootJar (이미지에 들어갈 jar 를 먼저 만든다)"
    rexec "./gradlew bootJar --console=plain" || die "bootJar 실패 — 이미지를 만들지 않는다"
}

# 지금 이 스택을 누가 쓰고 있는가. 있으면 멈춘다 — 조용히 갈아엎는 것이 최악이다.
# CI(자체 호스트 러너)가 e2e 잡에서 같은 컨테이너를 쓰므로 특히 겹친다.
#
# **`pgrep -f` 는 자기 자신을 잡는다.** 찾는 패턴이 이 프로브의 명령줄 안에 문자열로 들어 있어서,
# 그냥 쓰면 아무것도 안 도는데도 전부 "실행 중" 이 된다(처음 쓴 판이 실제로 그랬다 —
# 넷 다 걸렸다). PID 로 거르는 것으로는 부족하다. 명령 치환이 포크한 자식은 명령줄이 같고
# PID 는 다르기 때문이다. 그래서 **프로브에만 있는 표식**을 심고 그 표식이 있는 줄을 버린다.
assert_stack_free() {
    local busy
    busy=$(ssh -o ConnectTimeout=10 "$REMOTE" '
        marker=stove_stack_probe_self
        busy_if() { pgrep -af "$1" 2>/dev/null | grep -v "$marker" | grep -q . && echo "$2"; }
        busy_if "Runner.Worker" "CI 잡이 실행 중이다"
        for p in run-scenario.sh watch-alerts.sh recover-license.sh; do
            busy_if "$p" "측정이 실행 중이다: $p"
        done' 2>/dev/null)
    [ -z "$busy" ] && return 0
    printf '\033[31m✗\033[0m 원격 스택이 사용 중입니다 — 지금 올리면 상대의 작업을 갈아엎습니다.\n' >&2
    printf '%s\n' "$busy" | sed 's/^/    /' >&2
    printf '\n  끝나기를 기다리거나, 정말 진행하려면 STOVE_FORCE_STACK=1 을 줍니다.\n' >&2
    exit 1
}

do_stack() {
    local action="${1:-status}" target="${2:-all}"
    case "$action" in
        up)
            [ "${STOVE_FORCE_STACK:-0}" = 1 ] || assert_stack_free
            do_sync
            case "$target" in
                infra) say "인프라 10종";       rexec "docker compose ${INFRA[*]} up -d" ;;
                apps)  build_jars; say "앱 10종"; rexec "docker compose ${APPS[*]} up -d --build" ;;
                all)   say "인프라 + 앱 20종"
                       rexec "docker compose ${INFRA[*]} up -d" || die "인프라 기동 실패"
                       build_jars
                       rexec "docker compose ${APPS[*]} up -d --build" ;;
            esac
            # 게이트가 막으면 `stack up` 도 실패다. 뜬 것과 서비스 가능한 것은 다르다.
            rexec "bash scripts/stack-wait.sh" || die "게이트 불통과 — 스택이 서비스 가능한 상태가 아니다"
            ;;
        down)
            # 내리는 것이 올리는 것보다 위험하다 — 남이 쓰는 중이면 그 자리에서 끝난다.
            [ "${STOVE_FORCE_STACK:-0}" = 1 ] || assert_stack_free
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
  e2e                        인수 시나리오 관통 확인 (스택이 떠 있어야 함)
  logs <서비스> [-n N] [-g 패턴]
  http <메서드> <서비스:포트/경로> [본문]
  status                     원격 자원 상태
  shell                      원격 셸

설정(리포 밖):
  git config stove.remote    <ssh-별칭>
  git config stove.remotedir <경로>        기본값 ~/stove/<사용자>-<호스트>

  타깃을 회차마다 바꾸려면 환경변수가 우선한다:
      STOVE_REMOTE=lab ./scripts/remote.sh test

  작업 디렉터리 기본값에 머신 이름이 들어간다 — 개발 머신이 여럿이고 원격이 하나이므로,
  고정 경로를 쓰면 나중에 민 쪽이 앞의 작업본을 --delete 로 지운다.

전체 스택은 원격 한 대에 **한 벌**이다(인프라가 container_name 을 고정한다).
`stack up|down` 은 CI 잡이나 측정이 도는 중이면 멈춘다 — 넘기려면 STOVE_FORCE_STACK=1.

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
    e2e)    do_sync; rexec "./gradlew :e2e:e2eTest --console=plain" ;;
    logs)   do_logs "$@" ;;
    http)   do_http "${1:-GET}" "${2:-/}" "${3:-}" ;;
    status) do_status ;;
    shell)  ssh -t "$REMOTE" "cd '$RDIR' && exec \$SHELL -l" ;;
    ""|-h|--help|help) usage ;;
    *)      die "알 수 없는 명령: $cmd  (--help 참고)" ;;
esac
