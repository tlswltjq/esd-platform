"""결정 116·139·decision-name-ids: 결정 번호·식별자·앵커·링크와 기준 판의 보존을 검사한다.

번호 결정은 LAST_NUMBER에서 끝났다. 1~LAST_NUMBER는 빈 번호를 허용하되 중복 없이 파일 순서대로 커져야 하고,
새 번호는 받지 않는다(기준 판에 없던 번호, LAST_NUMBER보다 큰 번호). 새 결정은 `<a id="식별자"></a>` 바로 뒤의
번호 없는 `## 제목`이고, 식별자는 영문 kebab 하나다. `[결정 식별자](…#앵커)` 링크는 문구와 앵커가 같아야 한다.
Python 3.8 이상, 외부 패키지 없음. 코드·주석은 줄 번호를 보존해 지운다.
링크가 아닌 '결정 N' 언급의 의미나 결정 본문 자체의 보존은 검사하지 않는다.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import unicodedata

HEADING = re.compile(r'^ {0,3}## +(\d+)\. +\S.*$')
NUMERIC_HEADING = re.compile(r'^ {0,3}##[ \t]+\d')
RAW_HEADING = re.compile(r'^ {0,3}## +\d+\.')
NAMED_HEADING = re.compile(r'^ {0,3}## +(?!\d)\S')
DECISION_ID = re.compile(r'^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$')
ANCHOR = re.compile(r'<a\s+id=["\']([^"\']+)["\']\s*>\s*</a>')
LINK = re.compile(r'\[결정 +(\d+(?:\s*[·,~]\s*\d+)*|[a-z][a-z0-9]*(?:-[a-z0-9]+)*)\]\(([^\s)]*)\)')
ANY_HEADING = re.compile(r'^ {0,3}#{1,6}(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$')
MARKDOWN_LINK = re.compile(r'!?\[([^\]]*)\]\([^)]*\)')
INLINE_CODE = re.compile(r'(`+)(?!`)(.*?)(?<!`)\1(?!`)')
FENCE = re.compile(r'^ {0,3}(`{3,}|~{3,})')
EXCLUDED = {'.git', 'build', 'node_modules'}
GUIDANCE = '나중에 병합하는 쪽(이 PR)이 번호를 바꾼다. main의 기존 번호·앵커는 유지한다.'
ORDER_GUIDANCE = '번호 결정은 번호 자리에 둔다(결정 139).'
NAMED_GUIDANCE = '새 결정은 번호 없이 `<a id="식별자"></a>`와 `## 제목`으로 쓴다(결정 decision-name-ids).'
# 결정 decision-name-ids: 번호를 쓴 마지막 결정. 이 뒤로 번호 결정을 더하지 않는다.
LAST_NUMBER = 238


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
    named = []
    anchors = {}
    anchor_lines = {}
    pending = []
    anchor_line = 0
    for line_number, line in enumerate(lines, 1):
        names = [match.group(1) for match in ANCHOR.finditer(line)]
        if names:
            anchor_line = line_number
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
        elif NAMED_HEADING.match(line) and pending:
            if anchor_line != line_number - 1:
                # PR #259 R2: review_packet.py는 앵커 바로 다음 줄만 이름 결정 제목으로 읽는다.
                errors.append('docs/decisions.md:{}: 식별자 앵커 바로 다음 줄이 제목이어야 한다'.format(line_number))
            if len(pending) != 1:
                errors.append('docs/decisions.md:{}: 식별자 앵커는 하나여야 한다: {}'.format(
                    line_number, ','.join(pending)))
            for name in pending:
                if not DECISION_ID.match(name):
                    errors.append('docs/decisions.md:{}: 식별자 형식 오류 {} (영문 소문자·숫자 kebab, 글자로 시작)'.format(
                        line_number, name))
                anchors.setdefault(name, name)
            named.append((pending[0], line_number))
            pending = []
        elif ANCHOR.sub('', line).strip():
            pending = []
    return records, anchors, anchor_lines, errors, named


CODE_SPAN = re.compile(r'(`+)(.+?)\1')
STAR_EMPHASIS = re.compile(r'(?<!\*)(\*{1,3})(?!\*)(\S(?:.*?\S)?)(?<!\*)\1(?!\*)')
# `_`는 단어 경계에서만 강조다. 앞뒤가 글자·숫자·`_`면 강조가 아니다(snake_case, snake__case__name).
UNDERSCORE_EMPHASIS = re.compile(r'(?<![0-9A-Za-z가-힣_])(_{1,3})(?!_)(\S(?:.*?\S)?)(?<!_)\1(?![0-9A-Za-z가-힣_])')
CODE_MARK = '\x00{}\x00'
CODE_MARK_PATTERN = re.compile('\x00(\\d+)\x00')


def rendered_text(heading):
    """헤딩이 화면에 보이는 글자. 인라인 코드는 자리표시로 가린 채 링크·강조 문법을 벗기고, 마지막에 코드 내용을
    그대로 되돌린다. 강조가 코드를 감싸도 한 번에 벗겨진다(PR #233 R2·재리뷰)."""
    codes = []

    def mask(match):
        codes.append(match.group(2))
        return CODE_MARK.format(len(codes) - 1)

    text = CODE_SPAN.sub(mask, heading)
    text = MARKDOWN_LINK.sub(lambda m: m.group(1), text)
    text = STAR_EMPHASIS.sub(lambda m: m.group(2), text)
    text = UNDERSCORE_EMPHASIS.sub(lambda m: m.group(2), text)
    return CODE_MARK_PATTERN.sub(lambda m: codes[int(m.group(1))], text)


def slug(heading):
    """GitHub 자동 앵커(github-slugger): 보이는 글자를 소문자로 바꾼 뒤 글자·숫자·결합 문자·`_`·`-`·공백 밖을 지우고 공백을 `-`로 바꾼다."""
    text = rendered_text(heading.strip()).lower()
    kept = ''.join(char for char in text
                   if char in '_- ' or unicodedata.category(char)[0] in 'LNM')
    return kept.replace(' ', '-')


def unique_slug(base, occurrences):
    """github-slugger와 같은 중복 처리: 이미 쓴 앵커(접미사가 붙은 것 포함)와 겹치지 않을 때까지 -N을 올린다(PR #233 R1)."""
    name = base
    while name in occurrences:
        occurrences[base] += 1
        name = '{}-{}'.format(base, occurrences[base])
    occurrences[name] = 0
    return name


def auto_anchors(text):
    """PR #233 R3: 실제 결정 헤딩의 자동 앵커만 번호로 푼다. 같은 slug는 GitHub처럼 -1, -2를 붙인다."""
    lines, _ = visible_lines(text)
    occurrences = {}
    anchors = {}
    for raw, line in zip(text.splitlines(), lines):
        if not ANY_HEADING.match(line):
            continue
        # 화면 줄은 인라인 코드를 지우므로 slug는 원문 줄에서 만든다.
        name = unique_slug(slug(ANY_HEADING.match(raw).group(1) or ''), occurrences)
        decision = HEADING.match(line)
        if decision:
            anchors[name] = int(decision.group(1))
    return anchors


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
    decisions_text = decisions.read_text(encoding='utf-8')
    records, anchors, anchor_lines, errors, named = parse_decisions(decisions_text)
    automatic = auto_anchors(decisions_text)
    seen = {}
    previous = 0
    for number, line, names in records:
        if number in seen:
            first_line, first_names = seen[number]
            errors.append('docs/decisions.md:{}: 중복 번호 {} (앵커 {}), 첫 헤딩 {}행 (앵커 {}). {}'.format(
                line, number, ','.join(names) or '없음', first_line, ','.join(first_names) or '없음', GUIDANCE))
        elif number < 1:
            errors.append('docs/decisions.md:{}: 순서 오류 번호 {} (기대 1 이상). {}'.format(line, number, GUIDANCE))
        elif number < previous:
            errors.append('docs/decisions.md:{}: 역순 번호 {} (앞 번호 {}). {}'.format(
                line, number, previous, ORDER_GUIDANCE))
        if number > LAST_NUMBER:
            errors.append('docs/decisions.md:{}: 번호 {} — 번호 결정은 {}에서 끝났다. {}'.format(
                line, number, LAST_NUMBER, NAMED_GUIDANCE))
        seen.setdefault(number, (line, names))
        previous = max(previous, number)
    if not records:
        errors.append('docs/decisions.md: 결정 헤딩이 없다 (기대 번호 1)')
    if base_text is not None:
        base_records, base_anchors, _, _, _ = parse_decisions(base_text)
        for name, number in base_anchors.items():
            if anchors.get(name) != number:
                errors.append('기준 판 결정 {} 앵커 {}가 변경·삭제됨 (현재 {}). {}'.format(
                    number, name, anchors.get(name, '없음'), GUIDANCE))
        for number, line, names in base_records:
            if not names and number not in seen:
                errors.append('기준 판 {}행 결정 {}가 삭제됨. {}'.format(line, number, GUIDANCE))
        base_numbers = {number for number, _, _ in base_records}
        for number, line, _ in records:
            if number not in base_numbers:
                errors.append('docs/decisions.md:{}: 새 번호 {}. {}'.format(line, number, NAMED_GUIDANCE))
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
                actual = anchors.get(anchor, automatic.get(anchor))
                label = link.group(1)
                checked += 1
                if actual is None:
                    errors.append('{}:{}: {} — 없는 앵커 {}'.format(
                        path.relative_to(root), line_number, link.group(), anchor))
                elif DECISION_ID.match(label):
                    if actual != label:
                        errors.append('{}:{}: {} — 식별자 {}와 앵커 {}가 다르다'.format(
                            path.relative_to(root), line_number, link.group(), label, anchor))
                elif isinstance(actual, str):
                    errors.append('{}:{}: {} — 실제 식별자 {}'.format(
                        path.relative_to(root), line_number, link.group(), actual))
                elif not label_contains(label, actual):
                    errors.append('{}:{}: {} — 실제 번호 {}'.format(
                        path.relative_to(root), line_number, link.group(), actual))
    return errors, (len(records) + len(named), max(seen, default=0), len(anchor_lines), checked)


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
            # PR #232 R2: 기준 판을 지정했는데 못 읽으면 보존 검사를 못 한 것이다. 통과로 바꾸지 않는다.
            print('실패: 기준 판 {}의 docs/decisions.md를 읽지 못해 번호·앵커 보존을 검사할 수 없다. '
                  'checkout의 fetch-depth와 --base-ref를 확인한다. 기준 판 없이 검사하려면 --base-ref를 빼고 돌린다.'
                  .format(args.base_ref))
            return 1
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
