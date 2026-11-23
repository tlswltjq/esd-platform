"""결정 213: PR의 필수 검사를 기다리고, 병합 조건(AGENTS 5절)을 판정해 하나씩 병합한다.

    wait   필수 검사가 등록될 때까지 기다린 뒤(push 직후 바로 감시하면 즉시 끝난다, PR #122),
           모두 끝날 때까지 기다린다. `--head-sha`를 주면 PR head가 그 커밋이 된 뒤부터 센다.
           종료 코드: 0 모두 pass, 1 실패·건너뜀·취소, 3 시간 초과.
    ready  draft PR을 `gh pr ready`로 바꾸고 wait한다. head가 같아 이전 결과(draft 때 SKIPPED build)가
           남으므로, 바꾸기 전 검사 job 링크를 기록해 두고 ready가 다시 돌리는 `build`는 새 링크가 나올
           때까지 기다린다. `review-gate`는 다시 돌지 않으니 같은 head의 최신 결과를 쓴다(PR #226 R8·R9).
    edit   `--body-file`로 본문을 바꾸고 wait한다. 본문 수정은 `review-gate`만 다시 돌린다. 본문이 같으면
           검사가 돌지 않으므로 기다리지 않고 지금 결과로 wait한다.
           (ci.yml은 ready_for_review에, review-gate.yml은 edited에 돈다. 트리거를 바꾸면 RERUN도 바꾼다.)
    check  병합 조건을 판정만 한다. 여러 PR을 주면 모두 판정한다.
    merge  주어진 순서대로 판정 → `gh pr merge --merge` → MERGED 확인을 하나씩 한다.
           한 PR이라도 조건을 못 채우거나 병합이 실패하면 거기서 멈춘다.

병합 조건(결정 107·112·118·137):
- 열린 PR이고 draft가 아니다(draft의 건너뛴 build는 CLEAN으로 보여도 통과가 아니다).
- `mergeStateStatus`가 CLEAN이다.
- head 커밋에서 가장 최근에 시작한 `build`·`review-gate` 검사가 모두 COMPLETED·SUCCESS다.
- `리뷰 판정` 표에 사용자 판정이 남아 있지 않다: 판정이 부분 반영·기각이거나, 지적이 high이거나,
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
from pathlib import Path
import re
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import review_gate  # noqa: E402

REQUIRED = ('build', 'review-gate')
RERUN = {'ready': ('build',), 'edit': ('review-gate',)}
USER_JUDGED = ('부분 반영', '기각')
HIGH = re.compile(r'\bhigh\b', re.IGNORECASE)
# 인용 안에 따옴표·공백이 아닌 글자가 하나 이상 있어야 한다(PR #226 재리뷰: \S는 따옴표도 센다).
USER_CONFIRMED = re.compile(r'사용자 확정\s*[:：]\s*["“][^"”]*[^"”\s][^"”]*["”]')
POLICY = '정책'
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
        if 'no' in err.lower() and 'check' in err.lower():
            return []
        raise SystemExit('실패: 필수 검사를 읽지 못함: ' + err.strip())


def registered(checks):
    # 한쪽만 먼저 등록·완료돼도 통과로 끝내지 않는다(PR #226 R6).
    names = [check.get('name') or '' for check in checks]
    return all(any(name.startswith(prefix) for name in names) for prefix in REQUIRED)


def check_summary(checks):
    buckets = [check.get('bucket') for check in checks]
    if not registered(checks) or 'pending' in buckets:
        return 'pending'
    return 'pass' if all(bucket == 'pass' for bucket in buckets) else 'fail'


def fresh(checks, renew, old_links):
    # 다시 도는 검사(renew)는 이벤트 전에 있던 job(링크)을 버린다. 시간 여유가 아니라 실행 세대로 구분한다.
    return [check for check in checks
            if not ((check.get('name') or '').startswith(renew) and check.get('link') in old_links)]


def links(checks):
    return {check.get('link') for check in checks if check.get('link')}


def wait(args, renew=(), old_links=frozenset()):
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
        if not registered(checks) and time.time() > register_deadline:
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
            if view(args.number)['isDraft']:
                print('실패: draft PR이라 build가 건너뛰었다. 검증을 마쳤으면 `gh pr ready`를 먼저 한다(결정 137).')
            else:
                print('실패: 통과하지 않은 필수 검사가 있다.')
            return 1
        if time.time() > deadline:
            print('시간 초과: {}초 안에 필수 검사가 끝나지 않았다.'.format(args.timeout))
            return 3
        time.sleep(args.interval)


def ready(args):
    if not view(args.number)['isDraft']:
        print('실패: draft PR이 아니다. ready가 build를 다시 돌리지 않으니 wait를 쓴다.')
        return 2
    old = links(required_checks(args.number))
    gh(['pr', 'ready', str(args.number)])
    return wait(args, RERUN['ready'], old)


def edit(args):
    body = Path(args.body_file).read_text(encoding='utf-8')
    current = view(args.number).get('body') or ''
    old = links(required_checks(args.number))
    gh(['pr', 'edit', str(args.number), '--body-file', args.body_file])
    if body.strip() == current.strip():
        print('본문이 같아 review-gate가 다시 돌지 않는다. 지금 결과로 판정한다.')
        return wait(args)
    return wait(args, RERUN['edit'], old)


def latest(rollup, prefix):
    runs = [item for item in rollup or [] if item.get('__typename', 'CheckRun') == 'CheckRun'
            and (item.get('name') or '').startswith(prefix)]
    return max(runs, key=lambda item: item.get('startedAt') or '', default=None)


def pending_user_verdicts(body):
    lines = review_gate.section_lines(body or '')
    if lines is None:
        return []
    pending = []
    for row in review_gate.table_rows(lines):
        text = ' | '.join(row)
        finding = row[0]
        verdict = row[1].lstrip('*_ ') if len(row) > 1 else ''
        if review_gate.NO_FINDINGS in finding:
            continue
        needs_user = verdict.startswith(USER_JUDGED) or bool(HIGH.search(finding)) or POLICY in text
        if needs_user and not USER_CONFIRMED.search(verdict):
            pending.append(finding[:80])
    return pending


def problems(pr):
    found = []
    if pr.get('state') != 'OPEN':
        found.append('열린 PR이 아니다: ' + str(pr.get('state')))
    if pr.get('isDraft'):
        found.append('draft PR이다(`gh pr ready` 뒤 build를 다시 기다린다)')
    if pr.get('mergeStateStatus') != 'CLEAN':
        found.append('mergeStateStatus가 CLEAN이 아니다: ' + str(pr.get('mergeStateStatus')))
    for name in REQUIRED:
        run = latest(pr.get('statusCheckRollup'), name)
        if run is None:
            found.append('head에 `' + name + '` 검사가 없다')
        elif run.get('status') != 'COMPLETED' or run.get('conclusion') != 'SUCCESS':
            found.append('head의 최근 `{}`가 {}·{}다'.format(name, run.get('status'), run.get('conclusion')))
    for finding in pending_user_verdicts(pr.get('body')):
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
    for number in args.numbers:
        pr = view(number)
        found = problems(pr)
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
        found = problems(pr)
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
