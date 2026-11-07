"""결정 114: 결정 번호 순서, 명시적 앵커 중복, 결정 링크 번호를 검사한다.

외부 패키지 없이 Python 3.8 이상에서 실행한다. 코드 블록·HTML 주석·인라인 코드는
줄 수를 보존하며 비워 예시를 제외한다. GitHub 자동 앵커는 검사하지 않는다.
"""
import argparse
import os
from pathlib import Path
import re
import sys

HEADING = re.compile(r'^ {0,3}## +(\d+)\.\s+.*$')
ANCHOR = re.compile(r'<a\s+id=["\']([^"\']+)["\']\s*>\s*</a>')
LINK = re.compile(r'\[결정 +(\d+)\]\(([^\s)]*)\)')
COMMENT = re.compile(r'<!--.*?(?:-->|\Z)', re.DOTALL)
INLINE_CODE = re.compile(r'(`+)(?!`)(.*?)(?<!`)\1(?!`)', re.DOTALL)
FENCE = re.compile(r'^ {0,3}(`{3,}|~{3,})')
EXCLUDED = {'.git', 'build', 'node_modules'}


def blank(match):
    return ''.join('\n' if char == '\n' else ' ' for char in match.group())


def document_lines(text):
    """실제 파일의 줄 번호를 보존한다. fence는 같은 문자·길이 이상의 닫힘만 인정한다."""
    lines = []
    fence = None
    for line in text.splitlines(keepends=True):
        match = FENCE.match(line)
        if fence is None and match:
            fence = match.group(1)
            lines.append('\n' if line.endswith('\n') else '')
        elif fence is not None:
            if match and match.group(1)[0] == fence[0] and len(match.group(1)) >= len(fence) \
                    and not line[match.end():].strip():
                fence = None
            lines.append('\n' if line.endswith('\n') else '')
        else:
            lines.append(line)
    text = COMMENT.sub(blank, ''.join(lines))
    return INLINE_CODE.sub(blank, text).splitlines()


def markdown_files(root):
    for directory, folders, files in os.walk(str(root), followlinks=False):
        folders[:] = sorted(name for name in folders if name not in EXCLUDED)
        for name in sorted(files):
            path = Path(directory) / name
            if name.endswith('.md') and not path.is_symlink():
                yield path


def check(root):
    root = Path(root).resolve()
    decisions = root / 'docs/decisions.md'
    lines = document_lines(decisions.read_text(encoding='utf-8'))
    errors = []
    numbers = []
    seen = set()
    anchors = {}
    anchor_lines = {}
    pending = []
    previous = 0
    for line_number, line in enumerate(lines, 1):
        for anchor in ANCHOR.finditer(line):
            name = anchor.group(1)
            if name in anchor_lines:  # rule: duplicate anchor
                errors.append('docs/decisions.md:{}: 중복 앵커 {} (첫 줄 {})'.format(
                    line_number, name, anchor_lines[name]))
            anchor_lines.setdefault(name, line_number)
            pending.append(name)
        heading = HEADING.match(line)
        if heading:
            number = int(heading.group(1))
            if number in seen:  # rule: duplicate number
                errors.append('docs/decisions.md:{}: 중복 번호 {}'.format(line_number, number))
            if number < previous:  # rule: reversed number
                errors.append('docs/decisions.md:{}: 역순 번호 {} (직전 {})'.format(
                    line_number, number, previous))
            if number != len(numbers) + 1:  # rule: sequential number
                errors.append('docs/decisions.md:{}: 순서 오류 번호 {} (기대 {})'.format(
                    line_number, number, len(numbers) + 1))
            for name in pending:
                anchors.setdefault(name, number)
            pending = []
            numbers.append(number)
            seen.add(number)
            previous = number
        elif line.strip() and not ANCHOR.fullmatch(line.strip()):
            pending = []  # 빈 줄 외의 본문이 끼면 바로 다음 헤딩의 앵커가 아니다.
    maximum = max(numbers, default=0)
    # 큰 번호 하나 때문에 거대한 range를 만들지 않고 누락 구간을 보고한다.
    expected = 1
    for number in sorted(seen):
        if number > expected:  # rule: missing number
            missing = str(expected) if number == expected + 1 else '{}~{}'.format(expected, number - 1)
            errors.append('docs/decisions.md: 누락 번호 ' + missing)
        expected = number + 1
    if not numbers:
        errors.append('docs/decisions.md: 결정 헤딩이 없다 (기대 번호 1)')
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
                if anchor not in anchors:
                    continue
                checked += 1
                actual = anchors[anchor]
                if int(link.group(1)) != actual:  # rule: link number
                    errors.append('{}:{}: {} — 실제 번호 {}'.format(
                        path.relative_to(root), line_number, link.group(), actual))
    return errors, (len(numbers), maximum, len(anchor_lines), checked)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--root', required=True, type=Path, help='저장소 루트')
    args = parser.parse_args(argv)
    try:
        errors, counts = check(args.root)
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
