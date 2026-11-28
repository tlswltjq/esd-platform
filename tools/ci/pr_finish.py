"""결정 213: PR의 필수 검사를 기다리고, 병합 조건(AGENTS 5절)을 판정해 하나씩 병합한다.

    wait   필수 검사가 등록될 때까지 기다린 뒤(push 직후 바로 감시하면 즉시 끝난다, PR #122),
           모두 끝날 때까지 기다린다. `--head-sha`를 주면 PR head가 그 커밋이 된 뒤부터 센다.
           종료 코드: 0 모두 pass, 1 실패·건너뜀·취소, 3 시간 초과. 같은 이름의 검사는 가장 최근 job만 본다.
    ready  draft PR을 `gh pr ready`로 바꾸고 wait한다. 결정 build-on-draft로 draft에서도 build가 돌고 ready는
           build를 다시 돌리지 않으므로, 같은 head의 결과로 판정한다.
    edit   `--body-file`로 본문을 바꾸고 wait한다. 본문 수정은 `review-gate`만 다시 돌린다. 본문이 글자 그대로
           같으면 보내지 않고 지금 결과로 wait한다. 보냈으면 공백만 달라도 새 review-gate를 기다린다.
           (review-gate.yml은 edited에 돈다. 트리거를 바꾸면 RERUN도 바꾼다.)
    check  병합 조건을 판정만 한다. 여러 PR을 주면 모두 판정한다.
    merge  주어진 순서대로 판정 → `gh pr merge --merge` → MERGED 확인을 하나씩 한다.
           한 PR이라도 조건을 못 채우거나 병합이 실패하면 거기서 멈춘다.

병합 조건(결정 107·112·118·137·ai-judges-adopted-high·stop-merge-on-red-main):
- main push CI 가운데 성공·실패로 끝난 가장 최근 실행이 실패가 아니다(취소·건너뜀은 해소가 아니다). 실패면
  본문(주석 밖)에 `- main CI 복구: <그 run URL>` 줄을 적은, 그 실패를 고치는 PR만 병합한다.
- 열린 PR이고 draft가 아니다(draft는 작업 중이다).
- `mergeStateStatus`가 CLEAN이다.
- head 커밋에서 가장 최근에 시작한 `build`·`review-gate` 검사가 모두 COMPLETED·SUCCESS다.
- `리뷰 판정` 표에 사용자 판정이 남아 있지 않다: 판정이 부분 반영·기각이거나, 채택·이관하지 않은 high이거나,
  지적·판정·이유에 "정책"이 들어간 행은 **판정 칸**에 비어 있지 않은 `사용자 확정: "<원문>"`이 있어야 한다
  (`사용자 확정 대기`·이유 칸의 언급은 확정이 아니다, PR #226 R2). "정책" 낱말 검사는 정책 변경 지적을
  놓치지 않으려는 보수적 근사다(R3). 낱말 없이 정책을 바꾸는 지적은 사람이 확인한다.

    python3 tools/ci/pr_finish.py wait 226 --head-sha "$(git rev-parse HEAD)"
    python3 tools/ci/pr_finish.py ready 226
    python3 tools/ci/pr_finish.py edit 226 --body-file body.md
    python3 tools/ci/pr_finish.py merge 224 225
"""
import argparse
import json
import re
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import review_gate  # noqa: E402

REQUIRED = ('build', 'review-gate')
RERUN = {'edit': ('review-gate',)}
MAIN_FAILURES = ('failure', 'timed_out', 'startup_failure')
MAIN_SUCCESS = 'success'
MAIN_FIX = re.compile(r'^\s*(?:[-*]\s+)?main CI 복구\s*[:：]\s*(\S+)', re.MULTILINE)
VIEW_FIELDS = 'number,state,isDraft,mergeStateStatus,headRefOid,body,statusCheckRollup'


def gh(args, check=True):
    result = subprocess.run(['gh'] + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode != 0:
        raise SystemExit('실패: gh ' + ' '.join(args) + '\n' + result.stderr.decode('utf-8', 'replace').strip())
    return result.returncode, result.stdout.decode('utf-8'), result.stderr.decode('utf-8', 'replace')


def view(number):
    return json.loads(gh(['pr', 'view', str(number), '--json', VIEW_FIELDS])[1])


def required_checks(number):
    code, out, err = gh(['pr', 'checks', str(number), '--required', '--json', 'name,bucket,state,link'], check=False)
    try:
        return json.loads(out) if out.strip() else []
    except ValueError:
        # 검사가 아직 없으면 gh는 JSON 대신 안내를 낸다.
        if no_checks(err):
            return []
        raise SystemExit('실패: 필수 검사를 읽지 못함: ' + err.strip())


def registered(checks):
    # 한쪽만 먼저 등록·완료돼도 통과로 끝내지 않는다(PR #226 R6).
    names = [check.get('name') or '' for check in checks]
    return all(any(name.startswith(prefix) for name in names) for prefix in REQUIRED)


# 결정 217: 필수 검사 build는 gradle 조각이 모두 끝난 뒤에야 등록된다(약 7분). 그 전에도 CI가 시작됐는지는 필수가
# 아닌 plan·gradle 검사로 안다. 이것이 이벤트 뒤에 생겼으면 등록 시간 초과로 끝내지 않고 전체 시간 안에서 기다린다.
STARTED = ('plan (', 'gradle (')


def listed_checks(number, strict=False):
    """필수가 아닌 검사까지 모두(`--required` 없음). 검사가 없으면 gh가 종료 코드 1과 안내를 내므로 빈 목록.
    strict면 그 밖의 조회 실패에서 멈춘다 — 이벤트 전 기록이 비면 이전 plan을 새 CI로 오인한다(PR #234 4차 리뷰)."""
    code, out, err = gh(['pr', 'checks', str(number), '--json', 'name,link,bucket'], check=False)
    if not out.strip():
        if strict and code != 0 and not no_checks(err):
            fail(err)
        return []
    try:
        return json.loads(out)
    except ValueError:
        if strict:
            fail(err)
        return []


def no_checks(err):
    # gh가 검사가 없을 때 내는 안내(`no checks reported on the '<branch>' branch`)만 인정한다. 낱말 포함만 보면
    # `no such host … check your internet connection` 같은 조회 실패를 검사 없음으로 읽는다(PR #234 5차 리뷰).
    return 'no checks reported' in err.lower()


def fail(err):
    raise SystemExit('실패: 검사 목록을 읽지 못함: ' + err.strip())


def ci_started(number, old_links=frozenset()):
    """CI가 실제로 돌고 있다: 건너뛰지 않은 plan·gradle 검사가 있고, old_links(이벤트 전
    링크)에 없다. 본문 수정은 커밋이 그대로라 이전 CI도 증거로 인정한다(old_links를 넘기지 않는다)."""
    return any((check.get('name') or '').startswith(STARTED) and check.get('bucket') != 'skipping'
               and check.get('link') not in old_links
               for check in listed_checks(number))


def missing_required(checks):
    names = [check.get('name') or '' for check in checks]
    return [prefix for prefix in REQUIRED if not any(name.startswith(prefix) for name in names)]


def check_summary(checks):
    buckets = [check.get('bucket') for check in checks]
    if not registered(checks) or 'pending' in buckets:
        return 'pending'
    return 'pass' if all(bucket == 'pass' for bucket in buckets) else 'fail'


def fresh(checks, renew, old_links):
    # 다시 도는 검사(renew)는 이벤트 전에 있던 job(링크)을 버린다. 시간 여유가 아니라 실행 세대로 구분한다.
    return [check for check in checks
            if not ((check.get('name') or '').startswith(renew) and check.get('link') in old_links)]


def job_order(check):
    """실행 세대의 순서: 링크의 GitHub job ID(`.../job/<번호>`). job ID는 계속 커지고 겹치지 않는다. 시작 시각은 초 단위라
    동률이 나 세대를 가르지 못한다(PR #245 2차 재리뷰). 링크가 없으면 None — 세대 비교에서 빼지도, 남을 빼지도 않는다."""
    found = re.search(r'(?:^|/)job/(\d+)', check.get('link') or '')
    return int(found.group(1)) if found else None


def latest_generation(checks):
    """같은 이름(전체 검사 이름)의 실행이 여럿이면 job ID가 가장 큰 것만 남긴다. 이름 앞부분으로 묶으면 실패한 `build (A)`를
    나중의 `build (B)` 성공이 지운다(PR #245 재리뷰 2)."""
    newest = {}
    for check in checks:
        order = job_order(check)
        if order is not None:
            name = check.get('name') or ''
            newest[name] = max(newest.get(name, order), order)
    return [check for check in checks
            if job_order(check) is None or job_order(check) >= newest[check.get('name') or '']]


def links(checks):
    return {check.get('link') for check in checks if check.get('link')}


def wait(args, renew=(), old_links=frozenset(), old_all=frozenset()):
    deadline = time.time() + args.timeout
    register_deadline = time.time() + args.register_timeout
    while True:
        if args.head_sha:
            head = view(args.number)['headRefOid']
            if head != args.head_sha:
                if time.time() > register_deadline:
                    print('시간 초과: PR head가 {}가 아니다({}). push했는지 확인한다.'.format(args.head_sha[:8], head[:8]))
                    return 3
                time.sleep(args.interval)
                continue
        checks = required_checks(args.number)
        if renew:
            checks = fresh(checks, renew, old_links)
        checks = latest_generation(checks)
        # 결정 217: 빠진 것이 늦게 등록되는 build뿐이고 이벤트 뒤 CI가 시작됐을 때만 등록 제한을 넘어 기다린다
        # (PR #234 3차 리뷰: 이벤트 전 plan·gradle은 old_all로, 건너뛴 검사는 bucket으로 뺀다).
        if not registered(checks) and time.time() > register_deadline and not (
                missing_required(checks) == ['build'] and ci_started(args.number, old_all)):
            print('시간 초과: {}초 동안 필수 검사({})가 모두 등록되지 않았다.'.format(
                args.register_timeout, ', '.join(REQUIRED)))
            return 3
        state = check_summary(checks)
        if state != 'pending':
            for check in checks:
                print('{}\t{}\t{}'.format(check.get('bucket'), check.get('name'), check.get('link', '')))
            if state == 'pass':
                print('통과: 필수 검사 {}개'.format(len(checks)))
                return 0
            print('실패: 통과하지 않은 필수 검사가 있다.')
            return 1
        if time.time() > deadline:
            print('시간 초과: {}초 안에 필수 검사가 끝나지 않았다.'.format(args.timeout))
            return 3
        time.sleep(args.interval)


def ready(args):
    if not view(args.number)['isDraft']:
        print('실패: draft PR이 아니다. wait를 쓴다.')
        return 2
    gh(['pr', 'ready', str(args.number)])
    return wait(args)


def edit(args):
    body = Path(args.body_file).read_text(encoding='utf-8')
    current = view(args.number).get('body') or ''
    if body == current:
        # 같으면 보내지 않는다. 보낸 뒤에는 공백만 달라도 새 job을 기다린다(PR #226 R10).
        print('본문이 같아 보내지 않았다. 지금 결과로 판정한다.')
        return wait(args)
    old = links(required_checks(args.number))
    gh(['pr', 'edit', str(args.number), '--body-file', args.body_file])
    # 본문 수정은 build를 다시 돌리지 않는다. 같은 커밋에서 돌고 있는 CI가 늦게 등록할 build를 기다린다.
    return wait(args, RERUN['edit'], old)


def latest(rollup, prefix):
    runs = [item for item in rollup or [] if item.get('__typename', 'CheckRun') == 'CheckRun'
            and (item.get('name') or '').startswith(prefix)]
    return max(runs, key=lambda item: item.get('startedAt') or '', default=None)


def main_ci_failure():
    """결정 stop-merge-on-red-main: main push CI 가운데 성공·실패로 끝난 가장 최근 실행이 실패면 {id, url, sha},
    아니면 None. 실패 뒤의 취소·건너뜀은 실패를 해소하지 않는다(PR #261 R1). 읽지 못하면 통과로 바꾸지 않고 멈춘다."""
    code, out, err = gh(['run', 'list', '--workflow', 'ci.yml', '--branch', 'main', '--event', 'push',
                         '--status', 'completed', '--limit', '30', '--json', 'databaseId,conclusion,headSha,url'],
                        check=False)
    if code != 0:
        raise SystemExit('실패: main CI 상태를 읽지 못함: ' + err.strip())
    for run in json.loads(out or '[]'):
        if run.get('conclusion') == MAIN_SUCCESS:
            return None
        if run.get('conclusion') in MAIN_FAILURES:
            return {'id': run['databaseId'], 'url': run.get('url'), 'sha': run.get('headSha')}
    return None


def fixes_main(pr, red):
    """PR #261 R2: 주석 밖의 `main CI 복구: <URL>` 줄이 이 저장소의 그 run URL과 정확히 같아야 고치는 PR이다."""
    body = re.sub(r'<!--.*?-->', '', pr.get('body') or '', flags=re.S)
    return any(link.rstrip('.,)') == red['url'] for link in MAIN_FIX.findall(body))


def problems(pr, red=None):
    found = []
    if red is not None and not fixes_main(pr, red):
        found.append('main CI가 실패해 있다(run {}, {}). 그 실패를 고치는 PR만 병합한다 — 고치는 PR은 본문에 '
                     '`- main CI 복구: {}` 줄을 적는다(결정 stop-merge-on-red-main)'.format(red['id'], red['url'], red['url']))
    if pr.get('state') != 'OPEN':
        found.append('열린 PR이 아니다: ' + str(pr.get('state')))
    if pr.get('isDraft'):
        found.append('draft PR이다(`pr_finish.py ready`로 바꾼다)')
    if pr.get('mergeStateStatus') != 'CLEAN':
        found.append('mergeStateStatus가 CLEAN이 아니다: ' + str(pr.get('mergeStateStatus')))
    for name in REQUIRED:
        run = latest(pr.get('statusCheckRollup'), name)
        if run is None:
            found.append('head에 `' + name + '` 검사가 없다')
        elif run.get('status') != 'COMPLETED' or run.get('conclusion') != 'SUCCESS':
            found.append('head의 최근 `{}`가 {}·{}다'.format(name, run.get('status'), run.get('conclusion')))
    for finding in review_gate.pending_user_verdicts(pr.get('body')):
        found.append('사용자 판정이 남은 지적: ' + finding)
    return found


def report(pr, found):
    if found:
        print('#{}: 병합 불가'.format(pr['number']))
        for item in found:
            print('  - ' + item)
    else:
        print('#{}: 병합 조건 충족 (head {})'.format(pr['number'], pr['headRefOid'][:8]))


def check(args):
    failed = 0
    red = main_ci_failure()
    for number in args.numbers:
        pr = view(number)
        found = problems(pr, red)
        report(pr, found)
        failed += bool(found)
    return 1 if failed else 0


def merged_state(number, attempts=5, delay=2):
    # 병합 직후 API가 잠깐 OPEN을 돌려줄 수 있다.
    state = None
    for _ in range(attempts):
        state = view(number)['state']
        if state == 'MERGED':
            return state
        time.sleep(delay)
    return state


def merge(args):
    for number in args.numbers:
        pr = view(number)
        # 앞 PR을 병합한 뒤 main CI가 바뀔 수 있어 PR마다 다시 읽는다.
        found = problems(pr, main_ci_failure())
        report(pr, found)
        if found:
            print('멈춤: #{}에서 멈췄다. 뒤 PR은 병합하지 않았다.'.format(number))
            return 1
        code, _, err = gh(['pr', 'merge', str(number), '--merge', '--match-head-commit', pr['headRefOid']], check=False)
        state = merged_state(number) if code == 0 else view(number)['state']
        if code != 0 or state != 'MERGED':
            print('멈춤: #{} 병합 실패(종료 코드 {}, 상태 {}): {}'.format(number, code, state, err.strip()))
            return 1
        print('#{}: MERGED'.format(number))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description='결정 213: 필수 검사 대기와 병합 조건 판정')
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('wait', 'ready', 'edit'):
        waiting = commands.add_parser(name)
        waiting.add_argument('number', type=int)
        waiting.add_argument('--timeout', type=int, default=3600)
        waiting.add_argument('--register-timeout', type=int, default=300)
        waiting.add_argument('--interval', type=int, default=15)
        if name == 'wait':
            waiting.add_argument('--head-sha', help='PR head가 이 커밋이 된 뒤부터 센다')
        else:
            waiting.set_defaults(head_sha=None)
        if name == 'edit':
            waiting.add_argument('--body-file', required=True)
    for name in ('check', 'merge'):
        sub = commands.add_parser(name)
        sub.add_argument('numbers', type=int, nargs='+', help='병합 순서대로')
    args = parser.parse_args(argv)
    return {'wait': wait, 'ready': ready, 'edit': edit, 'check': check, 'merge': merge}[args.command](args)


if __name__ == '__main__':
    sys.exit(main())
