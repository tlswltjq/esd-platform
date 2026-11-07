"""결정 114: 결정 번호·앵커·복합 링크와 기준 판의 번호 보존을 검사한다.

Python 3.8 이상, 외부 패키지 없음. 코드·주석은 줄 번호를 보존해 지운다.
링크가 아닌 '결정 N' 언급의 의미나 결정 본문 자체의 보존은 검사하지 않는다.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

HEADING = re.compile(r'^ {0,3}## +(\d+)\. +\S.*$')
NUMERIC_HEADING = re.compile(r'^ {0,3}##[ \t]+\d')
RAW_HEADING = re.compile(r'^ {0,3}## +\d+\.')
ANCHOR = re.compile(r'<a\s+id=["\']([^"\']+)["\']\s*>\s*</a>')
LINK = re.compile(r'\[결정 +(\d+(?:\s*[·,~]\s*\d+)*)\]\(([^\s)]*)\)')
AUTO_ANCHOR = re.compile(r'^(\d+)-')
INLINE_CODE = re.compile(r'(`+)(?!`)(.*?)(?<!`)\1(?!`)')
FENCE = re.compile(r'^ {0,3}(`{3,}|~{3,})')
EXCLUDED = {'.git', 'build', 'node_modules'}
GUIDANCE = '나중에 병합하는 쪽(이 PR)이 번호를 바꾼다. main의 기존 번호·앵커는 유지한다.'


def visible_lines(text):
    """주석 안의 fence는 무시한다. 주석은 짝이 있거나 줄 앞에서 시작할 때만 연다."""
    result = []
    fenced = set()
    fence = None
    comment = False
    for index, raw in enumerate(text.splitlines()):
        if fence is not None:
            match = FENCE.match(raw)
            if match and match.group(1)[0] == fence[0] and len(match.group(1)) >= len(fence) \
                    and not raw[match.end():].strip():
                fence = None
            result.append('')
            fenced.add(index)
            continue
        line = raw
        offset = 0
        while True:
            if comment:
                end = line.find('-->', offset)
                if end < 0:
                    line = line[:offset] + ' ' * (len(line) - offset)
                    break
                line = line[:offset] + ' ' * (end + 3 - offset) + line[end + 3:]
                offset = end + 3
                comment = False
            start = line.find('<!--', offset)
            if start < 0:
                break
            end = line.find('-->', start + 4)
            if end < 0 and line[:start].strip():
                # 줄 중간의 짝 없는 주석은 다음 문단을 숨기지 않는다.
                break
            offset = start
            comment = True
        match = FENCE.match(line)
        if match:
            fence = match.group(1)
            fenced.add(index)
            result.append('')
        else:
            result.append(INLINE_CODE.sub(lambda m: ' ' * len(m.group()), line))
    return result, fenced


def document_lines(text):
    return visible_lines(text)[0]


def markdown_files(root):
    """저장소에서는 추적 파일만 사용한다. git 없는 fixture/배포본은 os.walk로 대체한다."""
    try:
        top = subprocess.run(['git', '-C', str(root), 'rev-parse', '--show-toplevel'],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        if Path(os.fsdecode(top.stdout).strip()).resolve() == root:
            files = subprocess.run(['git', '-C', str(root), 'ls-files', '-z', '--', '*.md'],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            for name in files.stdout.split(b'\0'):
                if not name:
                    continue
                path = root / os.fsdecode(name)
                if path.is_file() and not any(part.is_symlink() for part in [path] + list(path.parents) if part != root):
                    yield path
            return
    except (OSError, subprocess.CalledProcessError):
        pass
    for directory, folders, files in os.walk(str(root), followlinks=False):
        folders[:] = sorted(name for name in folders if name not in EXCLUDED)
        for name in sorted(files):
            path = Path(directory) / name
            if name.endswith('.md') and not path.is_symlink():
                yield path


def parse_decisions(text):
    lines, fenced = visible_lines(text)
    errors = []
    for index, raw in enumerate(text.splitlines()):
        if RAW_HEADING.match(raw) and not lines[index].strip() and index not in fenced:
            errors.append('docs/decisions.md:{}: 코드 펜스 밖 헤딩이 지워짐'.format(index + 1))
    records = []
    anchors = {}
    anchor_lines = {}
    pending = []
    for line_number, line in enumerate(lines, 1):
        names = [match.group(1) for match in ANCHOR.finditer(line)]
        for name in names:
            if name in anchor_lines:
                errors.append('docs/decisions.md:{}: 중복 앵커 {} (첫 줄 {})'.format(
                    line_number, name, anchor_lines[name]))
            anchor_lines.setdefault(name, line_number)
        pending.extend(names)
        heading = HEADING.match(line)
        if heading:
            number = int(heading.group(1))
            records.append((number, line_number, tuple(pending)))
            for name in pending:
                anchors.setdefault(name, number)
            pending = []
        elif NUMERIC_HEADING.match(line):
            errors.append('docs/decisions.md:{}: 헤딩 형식 오류: {}'.format(line_number, line))
            pending = []
        elif ANCHOR.sub('', line).strip():
            pending = []
    return records, anchors, anchor_lines, errors


def label_contains(label, number):
    for part in re.split(r'[·,]', label):
        ends = [int(item.strip()) for item in part.split('~')]
        if len(ends) == 1 and ends[0] == number:
            return True
        if len(ends) == 2 and ends[0] <= number <= ends[1]:
            return True
    return False


def check(root, base_text=None):
    root = Path(root).resolve()
    decisions = root / 'docs/decisions.md'
    records, anchors, anchor_lines, errors = parse_decisions(decisions.read_text(encoding='utf-8'))
    seen = {}
    previous = 0
    for number, line, names in records:
        if number in seen:
            first_line, first_names = seen[number]
            errors.append('docs/decisions.md:{}: 중복 번호 {} (앵커 {}), 첫 헤딩 {}행 (앵커 {}). {}'.format(
                line, number, ','.join(names) or '없음', first_line, ','.join(first_names) or '없음', GUIDANCE))
        if number != previous + 1:
            kind = '역순 번호' if number < previous else '순서 오류 번호'
            missing = ' 누락 번호 {}'.format(previous + 1) if number > previous + 1 else ''
            errors.append('docs/decisions.md:{}: {} {} (기대 {}){}. {}'.format(
                line, kind, number, previous + 1, missing, GUIDANCE))
        seen.setdefault(number, (line, names))
        previous = number
    if not records:
        errors.append('docs/decisions.md: 결정 헤딩이 없다 (기대 번호 1)')
    if base_text is not None:
        base_records, base_anchors, _, _ = parse_decisions(base_text)
        for name, number in base_anchors.items():
            if anchors.get(name) != number:
                errors.append('기준 판 결정 {} 앵커 {}가 변경·삭제됨 (현재 {}). {}'.format(
                    number, name, anchors.get(name, '없음'), GUIDANCE))
        for number, line, names in base_records:
            if not names and number not in seen:
                errors.append('기준 판 {}행 결정 {}가 삭제됨. {}'.format(line, number, GUIDANCE))
    checked = 0
    for path in markdown_files(root):
        for line_number, line in enumerate(document_lines(path.read_text(encoding='utf-8')), 1):
            for link in LINK.finditer(line):
                target, separator, anchor = link.group(2).partition('#')
                if not separator:
                    continue
                if target:
                    if target != 'decisions.md' and not target.endswith('/decisions.md'):
                        continue
                elif path != decisions:
                    continue
                automatic = AUTO_ANCHOR.match(anchor)
                actual = anchors.get(anchor)
                if actual is None and automatic:
                    actual = int(automatic.group(1))
                checked += 1
                if actual is None:
                    errors.append('{}:{}: {} — 없는 앵커 {}'.format(
                        path.relative_to(root), line_number, link.group(), anchor))
                elif not label_contains(link.group(1), actual):
                    errors.append('{}:{}: {} — 실제 번호 {}'.format(
                        path.relative_to(root), line_number, link.group(), actual))
    return errors, (len(records), max(seen, default=0), len(anchor_lines), checked)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--root', required=True, type=Path, help='저장소 루트')
    parser.add_argument('--base-ref', help='번호와 앵커를 보존할 git 기준 판')
    args = parser.parse_args(argv)
    base_text = None
    if args.base_ref:
        try:
            base = subprocess.run(['git', '-C', str(args.root), 'show', args.base_ref + ':docs/decisions.md'],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            base_text = base.stdout.decode('utf-8')
        except (OSError, UnicodeError, subprocess.CalledProcessError):
            print('경고: 기준 판 {}을 읽지 못해 기준 판 비교만 건너뜀'.format(args.base_ref))
    try:
        errors, counts = check(args.root, base_text)
    except (OSError, UnicodeError) as error:
        print('실패: ' + str(error))
        return 1
    if errors:
        print('실패: 결정 번호 검사')
        for error in errors:
            print('- ' + error)
        return 1
    print('통과: 헤딩 {}개, 최대 번호 {}, 앵커 {}개, 검사한 링크 {}개'.format(*counts))
    return 0


if __name__ == '__main__':
    sys.exit(main())
