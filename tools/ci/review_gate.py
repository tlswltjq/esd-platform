"""결정 109: 결정 108의 교차 리뷰가 PR 본문 `리뷰 판정` 표에 적혔는지 확인한다.

변경 파일이 모두 *.md인 PR은 검사하지 않는다. 그 밖의 PR은 `## 리뷰 판정` 절의 표에 행이 하나 이상
있어야 하고, 지적마다 판정 칸이 채택·부분 반영·기각 중 하나로 시작해야 한다. 지적이 없으면
"지적 없음" 행을 쓴다. HTML 주석과 빈 행은 세지 않는다. 적힌 내용이 진짜인지는 확인하지 않는다.

    python3 -I -B tools/ci/review_gate.py --body body.md --files files.txt
"""
import argparse
from pathlib import Path
import re
import sys

CHECK_NAME = 'review-gate'
SECTION = '## 리뷰 판정'
VERDICTS = ('채택', '부분 반영', '기각')
NO_FINDINGS = '지적 없음'
COMMENT = re.compile(r'<!--.*?-->', re.DOTALL)
SEPARATOR_CELL = re.compile(r'^:?-{3,}:?$')
ESCAPED_PIPE = '\x00'


def needs_review(files):
    return any(not name.endswith('.md') for name in files)


def section_lines(body):
    lines = COMMENT.sub('', body.replace('\r\n', '\n')).split('\n')
    found = False
    collected = []
    for line in lines:
        if not found:
            found = line.strip() == SECTION
            continue
        if line.startswith('## '):
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
    return all(SEPARATOR_CELL.match(cell) for cell in row if cell) and any(row)


def table_rows(lines):
    table = [cells(line) for line in lines if line.strip().startswith('|')]
    rows = []
    for index, row in enumerate(table):
        followed_by_separator = index + 1 < len(table) and is_separator(table[index + 1])
        if is_separator(row) or followed_by_separator or not any(row):
            continue
        rows.append(row)
    return rows


def has_verdict(cell):
    return cell.lstrip('*_ ').startswith(VERDICTS)


def check(body, files):
    if not needs_review(files):
        return []
    lines = section_lines(body or '')
    if lines is None:
        return ['PR 본문에 `' + SECTION + '` 절이 없다.']
    rows = table_rows(lines)
    if not rows:
        return ['`리뷰 판정` 표가 비어 있다. 교차 리뷰의 지적과 판정을 적거나, 리뷰어와 "지적 없음"을 적는다.']
    errors = []
    for row in rows:
        finding = row[0]
        verdict = row[1] if len(row) > 1 else ''
        if NO_FINDINGS in finding:
            continue
        if not finding:
            errors.append('지적 칸이 빈 행이 있다: ' + ' | '.join(row))
        elif not has_verdict(verdict):
            errors.append('판정(채택·부분 반영·기각)이 없는 지적: ' + finding[:80])
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--body', required=True, help='PR 본문 파일')
    parser.add_argument('--files', required=True, help='변경 파일 목록(줄마다 하나)')
    args = parser.parse_args(argv)
    body = Path(args.body).read_text(encoding='utf-8')
    files = [line.strip() for line in Path(args.files).read_text(encoding='utf-8').splitlines() if line.strip()]
    if not needs_review(files):
        print('통과: 변경 파일 ' + str(len(files)) + '개가 모두 *.md라 교차 리뷰 대상이 아니다(결정 108).')
        return 0
    errors = check(body, files)
    if errors:
        print('실패: 코드가 바뀐 PR은 병합 전에 교차 리뷰를 받고 `리뷰 판정`에 적는다(결정 108·109).')
        for error in errors:
            print('- ' + error)
        return 1
    print('통과: `리뷰 판정` 행 ' + str(len(table_rows(section_lines(body)))) + '개, 모든 지적에 판정이 있다.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
