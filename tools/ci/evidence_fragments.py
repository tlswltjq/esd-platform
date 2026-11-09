#!/usr/bin/env python3
"""결정 146: 근거 색인을 PR별 조각으로 쓰고, 접기 PR만 docs/evidence.md를 고친다.

- 조각: `docs/evidence.d/<PR번호>.md`. 근거 색인 표와 같은 다섯 칸 행을 한 줄 이상 둔다.
  링크는 접힌 뒤의 위치(docs/evidence.md) 기준으로 쓴다.
- check : 저장소의 모든 조각 형식을 검사한다.
- pr    : PR의 바뀐 파일(`상태<TAB>경로`)로 규칙을 검사한다.
          · 코드 PR(`*.md` 밖의 파일을 바꿈)은 자기 번호 조각을 더하거나 고쳐야 한다.
          · 다른 번호의 조각을 더하거나 고치지 않는다. 조각 삭제는 접기 PR만 한다.
          · docs/evidence.md는 접기 PR만 고친다. 접기 PR은 docs/evidence.md와 조각 삭제만 담는다.
- fold  : 조각 행을 (날짜, PR 번호) 순으로 docs/evidence.md 끝에 붙이고 조각을 지운다.
"""
import argparse
import re
import sys
from pathlib import Path

INDEX = 'docs/evidence.md'
FRAGMENTS = 'docs/evidence.d'
NAME = re.compile(r'^docs/evidence\.d/(\d+)\.md$')
DATE = re.compile(r'^\d{4}-\d{2}-\d{2}$')
COLUMNS = 5


def cells(line):
    """`| a | b |` 행을 칸 목록으로 나눈다. GFM 표처럼 이스케이프한 `\\|`만 칸 경계가 아니다.
    리뷰 2: 인라인 코드 안이라도 이스케이프하지 않은 `|`는 GitHub에서 칸 경계다."""
    body = line.strip()
    if not (body.startswith('|') and body.endswith('|')) or len(body) < 2:
        return None
    parts, current, i = [], [], 1
    while i < len(body) - 1:
        ch = body[i]
        if ch == '\\' and i + 1 < len(body) - 1 and body[i + 1] == '|':
            current.append('\\|'); i += 2; continue
        if ch == '|':
            parts.append(''.join(current).strip()); current = []
        else:
            current.append(ch)
        i += 1
    parts.append(''.join(current).strip())
    return parts


def rows(path, text):
    """조각의 행 목록. 형식 오류는 (행, 오류) 중 오류 목록으로 돌려준다."""
    found, errors = [], []
    lines = [line for line in text.split('\n') if line.strip()]
    if not lines:
        errors.append('%s: 행이 없다' % path)
    for number, line in enumerate(lines, 1):
        parts = cells(line)
        if parts is None or len(parts) != COLUMNS:
            errors.append('%s:%d: 근거 색인 행은 `| 날짜 | 모듈 | 검증 대상 | 결과 | 근거 |` 다섯 칸이다' % (path, number))
        elif not DATE.match(parts[0]):
            errors.append('%s:%d: 날짜는 YYYY-MM-DD다: %s' % (path, number, parts[0]))
        elif not all(parts[1:]):
            errors.append('%s:%d: 빈 칸이 있다' % (path, number))
        else:
            found.append((parts[0], line.strip()))
    return found, errors


def fragment_files(root):
    folder = root / FRAGMENTS
    if not folder.is_dir():
        return []
    return sorted((p for p in folder.iterdir() if p.is_file()), key=lambda p: p.name)


def check(root):
    errors = []
    for path in fragment_files(root):
        relative = path.relative_to(root).as_posix()
        if not NAME.match(relative):
            errors.append('%s: 조각 이름은 docs/evidence.d/<PR번호>.md다' % relative)
            continue
        errors.extend(rows(relative, path.read_text(encoding='utf-8'))[1])
    return errors


def check_pr(number, changes):
    """changes: [(상태, 경로)]. 상태는 GitHub API 값(added·modified·removed·renamed·changed 등)."""
    errors = []
    own = 'docs/evidence.d/%d.md' % number
    code = [path for status, path in changes if not path.endswith('.md')]
    touched_index = any(path == INDEX for _, path in changes)
    fragments = [(status, path) for status, path in changes if path.startswith(FRAGMENTS + '/')]
    written = [path for status, path in fragments if status != 'removed']
    removed = [path for status, path in fragments if status == 'removed']
    if touched_index:
        others = [path for _, path in changes if path != INDEX and path not in removed]
        if others:
            errors.append('docs/evidence.md는 접기 PR만 고친다. 접기 PR은 docs/evidence.md와 조각 삭제만 담는다'
                          '(같이 바뀐 파일: %s). 근거 행은 %s에 쓴다' % (', '.join(sorted(others)), own))
        return errors
    if removed:
        errors.append('조각 삭제는 접기 PR만 한다: %s' % ', '.join(sorted(removed)))
    foreign = [path for path in written if path != own]
    if foreign:
        errors.append('이 PR(#%d)의 조각은 %s 하나다: %s' % (number, own, ', '.join(sorted(foreign))))
    if code and own not in written:
        errors.append('코드 PR은 근거 조각 %s를 더한다(결정 146)' % own)
    return errors


def fold(root):
    errors = check(root)
    if errors:
        return errors, 0
    gathered = []
    paths = fragment_files(root)
    for path in paths:
        number = int(NAME.match(path.relative_to(root).as_posix()).group(1))
        for date, line in rows(path, path.read_text(encoding='utf-8'))[0]:
            gathered.append((date, number, line))
    if not gathered:
        return [], 0
    index = root / INDEX
    text = index.read_text(encoding='utf-8').rstrip('\n') + '\n'
    text += ''.join(line + '\n' for _, _, line in sorted(gathered, key=lambda g: (g[0], g[1])))
    index.write_text(text, encoding='utf-8')
    for path in paths:
        path.unlink()
    return [], len(gathered)


def read_changes(path):
    """`상태<TAB>경로[<TAB>이전 경로]` 줄. 리뷰 1: 이름 변경은 이전 경로 삭제와 새 경로 추가로 나눠 양쪽에 규칙을 적용한다."""
    changes = []
    for line in Path(path).read_text(encoding='utf-8').split('\n'):
        if not line.strip():
            continue
        fields = [field.strip() for field in line.split('\t')]
        status, name = fields[0], fields[1]
        previous = fields[2] if len(fields) > 2 else ''
        if status == 'renamed' and previous:
            changes.append(('removed', previous))
            changes.append(('added', name))
        else:
            changes.append((status, name))
    return changes


def main(argv=None):
    parser = argparse.ArgumentParser(description='결정 146: 근거 색인 조각')
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('check', 'fold'):
        sub.add_parser(name).add_argument('--root', default='.')
    pr = sub.add_parser('pr')
    pr.add_argument('--number', type=int, required=True)
    pr.add_argument('--changes', required=True, help='`상태<TAB>경로` 줄 목록 파일')
    args = parser.parse_args(argv)
    if args.command == 'pr':
        errors = check_pr(args.number, read_changes(args.changes))
        ok = '통과: PR #%d 근거 조각 규칙' % args.number
    elif args.command == 'check':
        errors = check(Path(args.root).resolve())
        ok = '통과: 근거 조각 형식'
    else:
        errors, count = fold(Path(args.root).resolve())
        ok = '접음: 행 %d개를 docs/evidence.md에 붙이고 조각을 지웠다' % count
    if errors:
        print('실패: 근거 조각(결정 146)\n' + '\n'.join('- ' + e for e in errors), file=sys.stderr)
        return 1
    print(ok)
    return 0


if __name__ == '__main__':
    sys.exit(main())
