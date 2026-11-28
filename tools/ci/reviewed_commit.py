#!/usr/bin/env python3
"""결정 review-gate-reviewed-commit: 리뷰한 커밋 뒤에 리뷰받지 않은 코드 변경이 있는지 확인한다.

PR 본문 `## 리뷰 판정` 절에 `리뷰한 커밋: <sha>`를 적는다(재리뷰를 받으면 그 줄을 더한다. 마지막 줄을 쓴다).
PR이 대상 브랜치와 달라지게 한 파일(`git diff <base>...<head>`) 가운데 `*.md`가 아닌 파일의 내용이
그 커밋과 지금 head에서 다르면 실패한다.
- main을 PR 브랜치로 병합해 들어온, PR이 바꾸지 않은 파일은 세지 않는다.
- 병합 충돌 해소나 같은 파일의 자동 병합은 내용이 달라지므로 재리뷰 대상이다.
- 문서만 바뀐 PR은 검사하지 않는다(결정 108).
적힌 커밋에서 리뷰가 정말 있었는지는 확인하지 않는다. 리뷰어 확인은 로컬의 cross_review.py가 한다(결정 237).

    python3 -I -B tools/ci/reviewed_commit.py --body body.md --root . --base HEAD^1 --head HEAD^2
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import review_gate  # noqa: E402

LABEL = '리뷰한 커밋'
CITATION = re.compile(LABEL + r'\s*[:：]\s*`?([0-9a-fA-F]{7,40})\b')


def cited(body):
    """`리뷰 판정` 절(HTML 주석·코드 블록 제외)에 마지막으로 적힌 커밋. 없으면 None."""
    lines = review_gate.section_lines(body or '')
    if lines is None:
        return None
    found = CITATION.findall('\n'.join(lines))
    return found[-1].lower() if found else None


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True)


def changed_code(root, base, head):
    """PR이 대상 브랜치와 달라지게 한 파일 중 문서가 아닌 것."""
    result = git(root, 'diff', '--no-renames', '--name-only', '-z', '%s...%s' % (base, head))
    if result.returncode != 0:
        raise RuntimeError('PR의 변경 파일을 얻지 못했다: ' + result.stderr.strip())
    return sorted(path for path in result.stdout.split('\0') if path and not path.endswith('.md'))


def blob(root, revision, path):
    result = git(root, 'rev-parse', '--verify', '--quiet', '%s:%s' % (revision, path))
    return result.stdout.strip() if result.returncode == 0 else None


def problems(root, base, head, reviewed):
    if git(root, 'rev-parse', '--verify', '--quiet', reviewed + '^{commit}').returncode != 0:
        return ['`%s: %s`를 이 저장소에서 찾을 수 없다.' % (LABEL, reviewed)]
    if git(root, 'merge-base', '--is-ancestor', reviewed, head).returncode != 0:
        return ['`%s: %s`는 이 PR 브랜치의 커밋이 아니다.' % (LABEL, reviewed)]
    if git(root, 'merge-base', '--is-ancestor', reviewed, base).returncode == 0:
        return ['`%s: %s`는 대상 브랜치에 이미 있는 커밋이라 이 PR 브랜치의 커밋이 아니다.' % (LABEL, reviewed)]
    stale = [path for path in changed_code(root, base, head) if blob(root, reviewed, path) != blob(root, head, path)]
    if not stale:
        return []
    return ['리뷰한 커밋 %s 뒤에 바뀐 코드 파일 %d개가 리뷰받지 않았다. 재리뷰(`review_packet.py --since %s`)를 받고 '
            '`%s:` 줄을 더한다: %s' % (reviewed[:12], len(stale), reviewed[:12], LABEL, ', '.join(stale[:20])
                                 + (' 외 %d개' % (len(stale) - 20) if len(stale) > 20 else ''))]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--body', required=True, help='PR 본문 파일')
    parser.add_argument('--root', default='.')
    parser.add_argument('--base', required=True, help='대상 브랜치 쪽 커밋(PR 병합 커밋의 첫 부모)')
    parser.add_argument('--head', required=True, help='PR 브랜치 head(PR 병합 커밋의 둘째 부모)')
    args = parser.parse_args(argv)
    try:
        code = changed_code(args.root, args.base, args.head)
    except RuntimeError as error:
        print('실패: ' + str(error))
        return 1
    if not code:
        print('통과: 문서만 바뀐 PR이라 리뷰한 커밋을 보지 않는다(결정 108).')
        return 0
    reviewed = cited(Path(args.body).read_text(encoding='utf-8'))
    if reviewed is None:
        errors = ['`## %s` 절에 `%s: <커밋>`이 없다. cross_review.py가 출력한 줄을 적는다.' % (review_gate.SECTION_TITLE, LABEL)]
    else:
        errors = problems(args.root, args.base, args.head, reviewed)
    if errors:
        print('실패: 리뷰한 커밋 뒤의 코드 변경은 재리뷰를 받는다(결정 review-gate-reviewed-commit).')
        for error in errors:
            print('- ' + error)
        return 1
    print('통과: 코드 파일 %d개가 리뷰한 커밋 %s와 같다.' % (len(code), reviewed[:12]))
    return 0


if __name__ == '__main__':
    sys.exit(main())
