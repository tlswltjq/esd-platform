"""결정 139: 새 결정 번호를 착수 때 예약한다.

다음 번호 = main의 docs/decisions.md 최대 번호와 열린 PR(draft 포함) 본문 `## 맡은 항목` 절의
`- 결정 번호:` 줄에 적힌 번호 중 가장 큰 값 + 1. 첫 줄에 번호만, 다음 줄에 근거를 출력한다.
번호를 받은 뒤 draft PR 본문에 적고 나서 그 번호로 결정을 묻거나 기록한다(AGENTS.md 1절).
열린 PR을 먼저 읽고 그다음 `git fetch origin main`으로 main을 새로 받는다. 두 읽기 사이에 병합된 PR은
PR 목록이나 새 main 중 한쪽에 반드시 보인다(교차 리뷰 R1). 본문은 decision_numbers와 같은 규칙으로
코드 블록·인라인 코드·HTML 주석을 지운 줄만 본다(R2).
Python 3.8 이상, 외부 패키지 없음. 열린 PR은 gh CLI로 읽는다.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import decision_numbers  # noqa: E402

SECTION = re.compile(r'^## +맡은 항목[ \t]*$')
NEXT_SECTION = re.compile(r'^## ')
NUMBER_LINE = re.compile(r'^- +결정 번호: *(.+)$')
NUMBER = re.compile(r'\d+')


def reserved_numbers(body):
    if not body:
        return []
    lines = decision_numbers.document_lines(body.replace('\r\n', '\n'))
    inside = False
    for line in lines:
        if SECTION.match(line):
            inside = True
            continue
        if inside and NEXT_SECTION.match(line):
            return []
        match = NUMBER_LINE.match(line) if inside else None
        if match is not None:
            return [int(value) for value in NUMBER.findall(match.group(1))]
    return []


def next_number(main_text, prs):
    records = decision_numbers.parse_decisions(main_text)[0]
    main_max = max((number for number, _, _ in records), default=0)
    reserved = {}
    for pr in prs:
        numbers = reserved_numbers(pr.get('body'))
        if numbers:
            reserved[pr['number']] = numbers
    highest = max([main_max] + [number for numbers in reserved.values() for number in numbers])
    return highest + 1, main_max, reserved


def read_main(root, base_ref, fetch):
    if fetch:
        fetched = subprocess.run(['git', '-C', str(root), 'fetch', '-q', 'origin', 'main'],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if fetched.returncode != 0:
            raise SystemExit('실패: origin main을 새로 받지 못함(오래된 main으로 번호를 내지 않는다): '
                             + fetched.stderr.decode('utf-8', 'replace').strip())
    result = subprocess.run(['git', '-C', str(root), 'show', base_ref + ':docs/decisions.md'],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise SystemExit('실패: ' + base_ref + '의 docs/decisions.md를 읽지 못함.')
    return result.stdout.decode('utf-8')


def read_open_prs(root):
    result = subprocess.run(['gh', 'pr', 'list', '--state', 'open', '--limit', '1000', '--json', 'number,body'],
                            cwd=str(root), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise SystemExit('실패: 열린 PR을 읽지 못함: ' + result.stderr.decode('utf-8', 'replace').strip())
    return json.loads(result.stdout.decode('utf-8'))


def main(argv=None):
    parser = argparse.ArgumentParser(description='결정 139: 다음 결정 번호(main 최대와 열린 PR 예약 중 큰 값 + 1)')
    parser.add_argument('--root', default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument('--base-ref', default='origin/main', help='main 결정 기록을 읽을 git 기준 판')
    parser.add_argument('--decisions', help='git 대신 읽을 decisions.md (테스트·오프라인용)')
    parser.add_argument('--prs-json', help='gh 대신 읽을 [{"number": N, "body": "..."}] (테스트·오프라인용)')
    parser.add_argument('--no-fetch', action='store_true', help='git fetch origin main을 건너뜀 (오프라인용)')
    args = parser.parse_args(argv)
    # 순서가 중요하다: 열린 PR을 먼저, main을 나중에 읽는다(교차 리뷰 R1).
    if args.prs_json:
        prs = json.loads(Path(args.prs_json).read_text(encoding='utf-8'))
    else:
        prs = read_open_prs(args.root)
    if args.decisions:
        main_text = Path(args.decisions).read_text(encoding='utf-8')
    else:
        main_text = read_main(args.root, args.base_ref, not args.no_fetch)
    number, main_max, reserved = next_number(main_text, prs)
    print(number)
    claims = ' · '.join('#{}: {}'.format(pr, ', '.join(str(n) for n in numbers)) for pr, numbers in sorted(reserved.items()))
    print('main 최대 {}, 열린 PR {}건 중 예약: {}'.format(main_max, len(prs), claims or '없음'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
