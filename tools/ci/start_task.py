"""결정 213: 착수 절차(결정 137·139)를 한 번에 준비한다.

    prepare  번호 예약 → origin/main에서 worktree·브랜치 생성 → 빈 착수 커밋 → PR 본문 파일 작성.
             마지막에 에이전트가 단독으로 실행할 `git push origin <브랜치>`를 출력한다.
             push는 스크립트가 하지 않는다(보안 정책이 push 대상을 직접 확인한다).
    open     push된 브랜치로 draft PR을 연다(본문은 prepare가 쓴 파일).

    python3 tools/ci/start_task.py prepare --branch feat/x --item '`.kiro/specs/order/tasks.md` — "항목 원문"' --decisions 1
    git push origin feat/x
    python3 tools/ci/start_task.py open --branch feat/x --title "feat(order): … (결정 214, 작업 중)"

Python 3.8 이상, 외부 패키지 없음. git·gh CLI를 쓴다.
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import next_decision_number  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = '.github/pull_request_template.md'
ITEM_LINE = re.compile(r'^- 항목: .*$', re.MULTILINE)
NUMBER_LINE = re.compile(r'^- 결정 번호: .*$', re.MULTILINE)
BRANCH = re.compile(r'^[A-Za-z0-9._/-]+$')
BODY_NAME = 'start_task_body.md'


def run(args, cwd=None):
    result = subprocess.run(args, cwd=None if cwd is None else str(cwd),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise SystemExit('실패: ' + ' '.join(args) + '\n' + result.stderr.decode('utf-8', 'replace').strip())
    return result.stdout.decode('utf-8')


def reserve(count, start):
    return list(range(start, start + count))


def fill_body(template, item, numbers):
    """템플릿의 `맡은 항목` 두 줄만 채운다. 다른 절과 안내 주석은 그대로 둔다."""
    if not ITEM_LINE.search(template) or not NUMBER_LINE.search(template):
        raise SystemExit('실패: PR 템플릿에 `- 항목:`·`- 결정 번호:` 줄이 없다.')
    value = ', '.join(str(number) for number in numbers) if numbers else '없음'
    body = ITEM_LINE.sub(lambda _: '- 항목: ' + item, template, count=1)
    return NUMBER_LINE.sub(lambda _: '- 결정 번호: ' + value, body, count=1)


def check_branch(branch):
    if not BRANCH.match(branch) or branch in ('main', 'master') or branch.startswith('-') or '..' in branch:
        raise SystemExit('실패: 브랜치 이름이 올바르지 않다(main 금지, 영문·숫자·._/-만): ' + branch)


def default_worktree(root, branch):
    return Path(root).resolve().parent / 'esd-worktrees' / branch.split('/')[-1]


def body_path(worktree):
    git_dir = run(['git', 'rev-parse', '--absolute-git-dir'], cwd=worktree).strip()
    return Path(git_dir) / BODY_NAME


def prepare(args):
    check_branch(args.branch)
    numbers = []
    if args.decisions:
        # 열린 PR을 먼저, main을 나중에 읽는다(결정 139 교차 리뷰 R1).
        prs = next_decision_number.read_open_prs(args.root)
        main_text = next_decision_number.read_main(args.root, True)
        start = next_decision_number.next_number(main_text, prs)[0]
        numbers = reserve(args.decisions, start)
    else:
        run(['git', '-C', str(args.root), 'fetch', '-q', 'origin',
             '+refs/heads/main:' + next_decision_number.MAIN_REF])
    worktree = Path(args.worktree) if args.worktree else default_worktree(args.root, args.branch)
    if worktree.exists():
        raise SystemExit('실패: worktree 경로가 이미 있다: ' + str(worktree))
    run(['git', '-C', str(args.root), 'worktree', 'add', '-q', '-b', args.branch, str(worktree), 'origin/main'])
    reserved = (' (결정 ' + ', '.join(str(n) for n in numbers) + ' 예약)') if numbers else ''
    message = 'chore: 착수' + reserved + '\n\n' + args.item + '\n'
    if args.trailer:
        message += '\n' + args.trailer + '\n'
    run(['git', 'commit', '-q', '--allow-empty', '-m', message], cwd=worktree)
    template = (worktree / TEMPLATE).read_text(encoding='utf-8')
    target = body_path(worktree)
    target.write_text(fill_body(template, args.item, numbers), encoding='utf-8')
    print('결정 번호: ' + (', '.join(str(n) for n in numbers) if numbers else '없음'))
    print('worktree: ' + str(worktree))
    print('PR 본문: ' + str(target))
    print('다음(단독 명령으로): git push origin ' + args.branch)
    return 0


def open_pr(args):
    check_branch(args.branch)
    worktree = Path(args.worktree) if args.worktree else default_worktree(args.root, args.branch)
    remote = run(['git', 'ls-remote', '--heads', 'origin', args.branch], cwd=worktree).strip()
    local = run(['git', 'rev-parse', 'HEAD'], cwd=worktree).strip()
    if not remote or remote.split()[0] != local:
        raise SystemExit('실패: origin/' + args.branch + '가 로컬 HEAD와 다르다. 먼저 `git push origin '
                         + args.branch + '`를 실행한다.')
    url = run(['gh', 'pr', 'create', '--draft', '--base', 'main', '--head', args.branch,
               '--title', args.title, '--body-file', str(body_path(worktree))], cwd=worktree).strip()
    print(url)
    print('검증을 마치면 `gh pr ready`로 바꾼다(draft에서는 build가 돌지 않는다, 결정 137).')
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description='결정 213: 착수 절차(결정 137·139)')
    parser.add_argument('--root', default=str(ROOT))
    commands = parser.add_subparsers(dest='command', required=True)
    first = commands.add_parser('prepare', help='번호 예약·worktree·착수 커밋·PR 본문')
    first.add_argument('--branch', required=True)
    first.add_argument('--item', required=True, help='`맡은 항목`의 항목 원문')
    first.add_argument('--decisions', type=int, default=0, help='예약할 결정 번호 개수')
    first.add_argument('--worktree', help='기본: <저장소 상위>/esd-worktrees/<브랜치 끝 이름>')
    first.add_argument('--trailer', default='', help='착수 커밋 끝 줄(예: 세션의 Co-Authored-By 줄)')
    second = commands.add_parser('open', help='push된 브랜치로 draft PR 생성')
    second.add_argument('--branch', required=True)
    second.add_argument('--title', required=True)
    second.add_argument('--worktree')
    args = parser.parse_args(argv)
    if args.command == 'prepare' and args.decisions < 0:
        parser.error('--decisions는 0 이상')
    return prepare(args) if args.command == 'prepare' else open_pr(args)


if __name__ == '__main__':
    sys.exit(main())
