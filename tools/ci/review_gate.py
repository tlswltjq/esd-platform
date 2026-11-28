"""결정 109: 결정 108의 교차 리뷰가 PR 본문 `리뷰 판정` 표에 적혔는지 확인한다.

변경 파일이 모두 *.md인 PR은 검사하지 않는다. 그 밖의 PR은 `## 리뷰 판정` 절에 표가 있어야 하고,
지적마다 판정 칸이 채택·부분 반영·기각·할 일로 이관 중 하나로 시작해야 한다. 지적이 없으면
`| 지적 없음 (리뷰어: …) |`처럼 리뷰어를 함께 적는다.
결정 112·ai-judges-adopted-high: 부분 반영·기각, 정책을 바꾸는 지적, 채택하지 않은 high는 판정 칸에
`사용자 확정: "원문"`이 있어야 한다. AI가 채택해 고치는 high는 `채택 (AI 판정)`이면 된다
(병합 도구 pr_finish.py도 이 함수를 쓴다).
결정 rereview-cap-two: 재리뷰 2회차 이후의 medium·low(지적 칸에 `재리뷰 N`, N ≥ 2)는 `할 일로 이관`과 옮긴 위치(`tasks.md`·`D-NNN`)를 적는다.
high는 이관하지 않는다.

변경 파일 목록은 줄마다 `경로` 또는 `경로<TAB>이전 경로`다. 이름 변경은 이전 경로도 리뷰 대상 판단에 넣는다
(코드 파일을 *.md로 옮겨도 코드 삭제이므로 리뷰 대상이다). 줄 수가 PR의 변경 파일 수다.

표는 템플릿 형식만 센다: 줄 앞뒤에 `|`를 쓰고, 머리 행 바로 다음 줄이 구분 행이며, 빈 줄로 끝난다.
코드 블록 안, HTML 주석, 빈 행, 다음 H1·H2 제목 뒤는 세지 않는다. 변경 파일 목록이 PR의 변경
파일 수와 다르면(API는 3,000개까지만 돌려준다) 리뷰 대상인지 판단할 수 없으므로 실패한다.
적힌 내용이 진짜인지는 확인하지 않는다.

    python3 -I -B tools/ci/review_gate.py --body body.md --files files.txt --expected-files 3
"""
import argparse
from pathlib import Path
import re
import sys

CHECK_NAME = 'review-gate'
SECTION_TITLE = '리뷰 판정'
ADOPTED = '채택'
MOVED = '할 일로 이관'
VERDICTS = (ADOPTED, '부분 반영', '기각', MOVED)
MOVED_TO = re.compile(r'tasks\.md|\bD-\d{3}\b')
REREVIEW = re.compile(r'재리뷰\s*(\d+)')
MOVE_FROM_REREVIEW = 2
NO_FINDINGS = '지적 없음'
COMMENT = re.compile(r'<!--.*?-->', re.DOTALL)
SECTION_START = re.compile(r'^ {0,3}## +' + SECTION_TITLE + r'\s*$')
SECTION_END = re.compile(r'^ {0,3}#{1,2}(\s|$)')
FENCE = re.compile(r'^ {0,3}(`{3,}|~{3,})')
SEPARATOR_CELL = re.compile(r'^:?-{3,}:?$')
REVIEWER = re.compile(r'리뷰어\s*[:：]\s*[^\s)\]|]')
ESCAPED_PIPE = '\x00'
USER_JUDGED = ('부분 반영', '기각')
# 심각도는 `R1 (high)`·`R8 (high, …)`처럼 괄호 바로 안의 첫 낱말이다. 리뷰어 표기의 추론 수준 high는 아니다.
HIGH = re.compile(r'\(\s*high\b', re.IGNORECASE)
# 인용 안에 따옴표·공백이 아닌 글자가 하나 이상 있어야 한다(PR #226 재리뷰: \S는 따옴표도 센다).
USER_CONFIRMED = re.compile(r'사용자 확정\s*[:：]\s*["“][^"”]*[^"”\s][^"”]*["”]')
POLICY = '정책'
TABLE_FORMAT = '표는 템플릿처럼 줄 앞뒤에 `|`를 쓰고, 머리 행 다음 줄에 구분 행(`|---|---|`)을 둔다.'


def needs_review(files):
    return any(not name.endswith('.md') for name in files)


def outside_fences(lines):
    """코드 블록의 줄을 빈 줄로 바꾼다. 빈 줄은 표를 끊는다."""
    result = []
    fence = None
    for line in lines:
        match = FENCE.match(line)
        if fence is None and match:
            fence = match.group(1)
            result.append('')
        elif fence is not None:
            if match and match.group(1)[0] == fence[0] and len(match.group(1)) >= len(fence) \
                    and not line.strip()[len(match.group(1)):].strip():
                fence = None
            result.append('')
        else:
            result.append(line)
    return result


def section_lines(body):
    lines = outside_fences(COMMENT.sub('', body.replace('\r\n', '\n')).split('\n'))
    found = False
    collected = []
    for line in lines:
        if not found:
            found = bool(SECTION_START.match(line))
            continue
        if SECTION_END.match(line):
            break
        collected.append(line)
    return collected if found else None


def cells(line):
    inner = line.strip().replace('\\|', ESCAPED_PIPE)
    if inner.startswith('|'):
        inner = inner[1:]
    if inner.endswith('|'):
        inner = inner[:-1]
    return [cell.strip().replace(ESCAPED_PIPE, '|') for cell in inner.split('|')]


def is_separator(row):
    return any(row) and all(SEPARATOR_CELL.match(cell) for cell in row if cell)


def table_blocks(lines):
    blocks = []
    current = []
    for line in lines + ['']:
        if line.strip().startswith('|'):
            current.append(cells(line))
        else:
            if current:
                blocks.append(current)
            current = []
    return blocks


def table_rows(lines):
    rows = []
    for block in table_blocks(lines):
        if len(block) < 2 or not is_separator(block[1]):
            continue
        rows.extend(row for row in block[2:] if any(row) and not is_separator(row))
    return rows


def has_verdict(cell):
    return cell.lstrip('*_ ').startswith(VERDICTS)


def pending_user_verdicts(body):
    """결정 112: 사용자가 판정해야 하는데 `사용자 확정: "원문"`이 없는 지적."""
    lines = section_lines(body or '')
    if lines is None:
        return []
    pending = []
    for row in table_rows(lines):
        text = ' | '.join(row)
        finding = row[0]
        verdict = row[1].lstrip('*_ ') if len(row) > 1 else ''
        if NO_FINDINGS in finding:
            continue
        high = bool(HIGH.search(finding))
        needs_user = verdict.startswith(USER_JUDGED) or POLICY in text \
            or (high and not verdict.startswith((ADOPTED, MOVED))) \
            or '사용자 확정' in verdict
        if needs_user and not USER_CONFIRMED.search(verdict):
            pending.append(finding[:80])
    return pending


def read_files(text):
    """`경로[<TAB>이전 경로]` 줄 목록 → (변경 항목 수, 리뷰 대상 판단에 쓸 경로 목록)."""
    entries = [line for line in text.splitlines() if line.strip()]
    names = [name.strip() for line in entries for name in line.split('\t') if name.strip()]
    return len(entries), names


def check(body, files, expected_files=None, entries=None):
    entries = len(files) if entries is None else entries
    if expected_files is not None and expected_files != entries:
        return ['변경 파일 목록이 ' + str(entries) + '개로 PR의 변경 파일 ' + str(expected_files)
                + '개와 다르다. 목록이 불완전하면 리뷰 대상인지 판단할 수 없다(API는 3,000개까지만 돌려준다).']
    if not needs_review(files):
        return []
    lines = section_lines(body or '')
    if lines is None:
        return ['PR 본문에 `## ' + SECTION_TITLE + '` 절이 없다.']
    rows = table_rows(lines)
    if not rows:
        return ['`' + SECTION_TITLE + '` 표가 비어 있다. 교차 리뷰의 지적과 판정을 적거나, '
                '`| 지적 없음 (리뷰어: …) |` 행을 적는다. ' + TABLE_FORMAT]
    errors = []
    for row in rows:
        finding = row[0]
        verdict = row[1] if len(row) > 1 else ''
        if NO_FINDINGS in finding:
            if not REVIEWER.search(' | '.join(row)):
                errors.append('"지적 없음" 행에 리뷰어가 없다. 예: `| 지적 없음 (리뷰어: Codex, 독립 문맥) | — | — |`')
        elif not finding:
            errors.append('지적 칸이 빈 행이 있다: ' + ' | '.join(row))
        elif not has_verdict(verdict):
            errors.append('판정(채택·부분 반영·기각·할 일로 이관)이 없는 지적: ' + finding[:80])
        elif verdict.lstrip('*_ ').startswith(MOVED):
            if HIGH.search(finding):
                errors.append('high는 이관하지 않는다(결정 rereview-cap-two). 이 PR에서 고친다: ' + finding[:80])
            elif max([int(n) for n in REREVIEW.findall(finding)] or [0]) < MOVE_FROM_REREVIEW:
                # PR #261 R3: 이관은 재리뷰 2회차(그 뒤 재리뷰를 하지 않는) 지적만 한다. 지적 칸에 `재리뷰 N`을 적는다.
                errors.append('`할 일로 이관`은 재리뷰 2회차 이후의 지적만 한다(지적 칸에 `재리뷰 N`): ' + finding[:80])
            elif not MOVED_TO.search(' | '.join(row)):
                errors.append('`할 일로 이관`에 옮긴 위치(`tasks.md` 또는 `D-NNN`)가 없다: ' + finding[:80])
    for finding in pending_user_verdicts(body):
        errors.append('사용자 판정이 남은 지적(결정 112·ai-judges-adopted-high: 부분 반영·기각·정책, '
                      '채택하지 않은 high는 판정 칸에 `사용자 확정: "원문"`): ' + finding)
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--body', required=True, help='PR 본문 파일')
    parser.add_argument('--files', required=True, help='변경 파일 목록(줄마다 `경로[<TAB>이전 경로]`)')
    parser.add_argument('--expected-files', type=int, help='PR의 변경 파일 수(API changed_files)')
    args = parser.parse_args(argv)
    body = Path(args.body).read_text(encoding='utf-8')
    entries, files = read_files(Path(args.files).read_text(encoding='utf-8'))
    errors = check(body, files, args.expected_files, entries)
    if errors:
        print('실패: 코드가 바뀐 PR은 병합 전에 교차 리뷰를 받고 `리뷰 판정`에 적는다(결정 108·109).')
        for error in errors:
            print('- ' + error)
        return 1
    if not needs_review(files):
        print('통과: 변경 파일 ' + str(entries) + '개가 모두 *.md라 교차 리뷰 대상이 아니다(결정 108).')
        return 0
    print('통과: `리뷰 판정` 행 ' + str(len(table_rows(section_lines(body)))) + '개, 모든 지적에 판정이 있다.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
