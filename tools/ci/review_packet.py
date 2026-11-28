#!/usr/bin/env python3
"""결정 145: Codex 교차 리뷰에 넘길 리뷰 묶음을 만든다.

묶음은 늘 같은 순서로 쌓는다(앞부분이 같으면 프롬프트 캐시가 맞을 가능성이 높다).
  1. 리뷰 지시(고정 문구)
  2. AGENTS.md 3절 코드 규칙
  3. 이 PR의 결정(본문 "이번 PR의 결정" 절·커밋 제목)은 원문, 그 밖에 언급된 결정은 제목 목록.
     결정은 번호(1~238)나 식별자(결정 decision-name-ids, `결정 spec-root`)로 찾는다
  4. 바뀐 모듈의 요구사항 중 R 번호가 언급된 절은 원문, 바뀐 운영 클래스 이름만 나오는 절은 제목 목록
  번호·식별자는 PR 본문(HTML 주석 제외)·커밋 메시지(병합 커밋 제외)·diff의 추가/삭제 줄에서만 센다.
  5. 이전 리뷰 지적(재리뷰일 때)
  6. diff — 운영 코드·이행 파일 → 테스트 → 그 밖 → 문서 순
  결정 packet-size-cap: 묶음이 SIZE_CAP(300KB)을 넘으면 문서(`*.md`) diff를 파일 목록(바뀐 줄 수)으로 바꾸고,
  문서 diff의 결정·R 번호는 세지 않은 채 다시 만든다. 그 사실을 묶음 머리에 적는다.
  결정 packet-deleted-files: 그래도 리뷰어 입력 한도(INPUT_CAP)를 넘으면 통째로 지운 파일의 diff도 파일 목록으로 바꾼다.

구현한 쪽의 설명이나 결론은 넣지 않는다(결정 112의 독립 문맥). 모으는 규칙은 기계적이다.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

INSTRUCTIONS = """# 리뷰 묶음 (결정 145)

읽기 전용 교차 리뷰다. 파일을 고치거나 커밋·push·PR 댓글을 하지 않는다.
- 아래 묶음은 이 PR이 언급한 결정·요구사항 원문과 diff다. 부족하면 저장소의 다른 파일을 직접 열어도 된다. 열었다면 결과 끝에 그 경로를 적는다.
- 테스트는 실행하지 않는다. CI와 PR 본문의 Red→Green 기록을 근거로 쓴다.
- 결과는 지적 표만 쓴다. 확인 범위를 다시 늘어놓지 않는다.

| 번호 | 심각도(high/medium/low) | 파일:줄 | 실패 시나리오(입력 → 잘못된 결과) | 고칠 방향 | 정책 변경 여부 |
|---|---|---|---|---|---|

지적이 없으면 `지적 없음` 한 줄만 쓴다.
"""

DECISION_REF = re.compile(r'결정\s*(\d+(?:\s*[·,/]\s*\d+)*)')
DECISION_ID_REF = re.compile(r'결정\s+`?([a-z][a-z0-9]*(?:-[a-z0-9]+)*)`?')
# decision_numbers.py의 ANCHOR와 같은 모양(따옴표 두 종류·공백)을 받는다(PR #259 R2).
NAMED_ANCHOR = re.compile(r'^\s*<a\s+id=["\']([a-z][a-z0-9]*(?:-[a-z0-9]+)*)["\']\s*>\s*</a>\s*$')
REQUIREMENT_REF = re.compile(r'(?<![A-Za-z0-9_])R(\d+)(?:[.\-]\d+)?(?![A-Za-z0-9_])')
SIZE_CAP = 300 * 1000
# Codex 한 번 입력 한도는 1,048,576자다(2026-11-28 PR #268에서 1,226,906자가 거부됨). 호출 문구 몫을 남긴다.
INPUT_CAP = 1000 * 1000
MODULE_PATH = re.compile(r'^(?:apps|specs)/([a-z0-9-]+)/')


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], check=True, capture_output=True, text=True).stdout


def read_at(root, rev, path):
    """리뷰 3: 기준 문서도 diff와 같은 판(`rev`)에서 읽는다. 없으면 ''."""
    result = subprocess.run(['git', '-C', str(root), 'show', '%s:%s' % (rev, path)], capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else ''


def file_blocks(diff):
    """리뷰 1: 전체 diff를 한 번 만들어 파일 블록으로 나눈다. 이름 변경도 한 블록(이전·이후 경로)으로 남는다."""
    blocks = []
    for chunk in re.split(r'(?m)^(?=diff --git )', diff):
        if not chunk.startswith('diff --git '):
            continue
        header = chunk.split('\n', 1)[0]
        match = re.match(r'diff --git a/(.*) b/(.*)$', header)
        blocks.append((match.group(2) if match else header, chunk.rstrip()))
    return blocks


def section(text, heading):
    """`heading`으로 시작하는 줄부터 같은 수준 이상의 다음 제목 앞까지를 돌려준다. 없으면 ''."""
    level = len(heading) - len(heading.lstrip('#'))
    lines = text.split('\n')
    out = []
    for line in lines:
        if out:
            stripped = line.lstrip('#')
            depth = len(line) - len(stripped)
            if (0 < depth <= level and stripped.startswith(' ')) or line.strip() == '---':
                break
            out.append(line)
        elif line.startswith(heading):
            out.append(line)
    # 다음 절의 앵커(<a id=...>)는 그 절의 것이다.
    while out and (not out[-1].strip() or out[-1].startswith('<a id=')):
        out.pop()
    return '\n'.join(out) + ('\n' if out else '')


def decision_numbers(*texts):
    found = set()
    for text in texts:
        for match in DECISION_REF.finditer(text or ''):
            found.update(int(n) for n in re.split(r'\s*[·,/]\s*', match.group(1)))
    return sorted(found)


def named_anchors(decisions):
    """식별자 → 앵커 줄 위치. 식별자 앵커 바로 다음 줄이 번호 없는 `## ` 헤딩일 때만 이름 결정이다."""
    lines = decisions.split('\n')
    found = {}
    for index, (anchor, heading) in enumerate(zip(lines, lines[1:])):
        match = NAMED_ANCHOR.match(anchor)
        if match and heading.startswith('## ') and not heading[3:4].isdigit():
            found.setdefault(match.group(1), index)
    return found


def decision_ids(decisions, *texts):
    named = named_anchors(decisions)
    found = set()
    for text in texts:
        found.update(m.group(1) for m in DECISION_ID_REF.finditer(text or '') if m.group(1) in named)
    return sorted(found)


def decision_keys(decisions, *texts):
    """번호(1~238) 다음에 식별자."""
    return decision_numbers(*texts) + decision_ids(decisions, *texts)


def heading_index(decisions, key):
    """결정 헤딩의 줄 위치. 식별자는 그 앵커 다음 줄이다(같은 제목·접두어 제목에 흔들리지 않는다, PR #259 R1)."""
    lines = decisions.split('\n')
    if isinstance(key, str):
        anchor = named_anchors(decisions).get(key)
        return None if anchor is None else anchor + 1
    return next((i for i, line in enumerate(lines) if line.startswith('## %d. ' % key)), None)


def decision_sections(decisions, keys):
    lines = decisions.split('\n')
    parts = []
    for key in keys:
        head = heading_index(decisions, key)
        body = section('\n'.join(lines[head:]), lines[head]) if head is not None else ''
        if not body:
            continue
        anchor = lines[head - 1] + '\n' if head > 0 and lines[head - 1].startswith('<a id=') else ''
        parts.append(anchor + body)
    return parts


def requirement_numbers(*texts):
    found = set()
    for text in texts:
        found.update(int(m.group(1)) for m in REQUIREMENT_REF.finditer(text or ''))
    return found


def modules(files):
    return sorted({m.group(1) for f in files for m in [MODULE_PATH.match(f)] if m})


def changed_classes(files):
    return sorted({Path(f).stem for f in files if f.endswith('.java') and '/src/main/' in f})


def requirement_sections(requirements, numbers, classes):
    """(원문으로 넣을 절, 제목만 넣을 절)."""
    full, titles = [], []
    for match in re.finditer(r'^### R(\d+)\. .*$', requirements, re.M):
        body = section(requirements, match.group(0))
        if int(match.group(1)) in numbers:
            full.append(body)
        elif any(re.search(r'\b%s\b' % re.escape(c), body) for c in classes):
            titles.append(match.group(0))
    return full, titles


def without_comments(text):
    return re.sub(r'<!--.*?-->', '', text or '', flags=re.S)


def changed_lines(diff_text):
    return '\n'.join(line for line in diff_text.split('\n')
                     if line[:1] in '+-' and not line.startswith(('+++', '---')))


def own_decisions(pr_body, subjects, decisions=''):
    """이 PR이 정한 결정: 본문 "## 이번 PR의 결정" 절과 커밋 제목의 결정 번호·식별자."""
    return decision_keys(decisions, section(without_comments(pr_body), '## 이번 PR의 결정'), subjects)


def decision_titles(decisions, keys):
    lines = decisions.split('\n')
    return ['- ' + lines[head][3:] for head in (heading_index(decisions, key) for key in keys) if head is not None]


def group(path):
    if path.endswith('.md'):
        return 3
    if '/src/main/' in path:
        return 0
    if re.search(r'/src/(test|integrationTest|testFixtures)/', path) or Path(path).name.startswith('test_'):
        return 1
    return 2


def counts(block):
    lines = block.split('\n')
    added = sum(1 for line in lines if line.startswith('+') and not line.startswith('+++'))
    removed = sum(1 for line in lines if line.startswith('-') and not line.startswith('---'))
    return added, removed


def build(root, base, head, since=None, pr_body='', previous='', size_cap=SIZE_CAP, input_cap=INPUT_CAP):
    packet, files, own = compose(root, base, head, since, pr_body, previous, list_docs=False)
    if len(packet.encode('utf-8')) <= size_cap:
        return packet, files, own
    packet, files, own = compose(root, base, head, since, pr_body, previous, list_docs=True)
    if len(packet) <= input_cap:
        return packet, files, own
    return compose(root, base, head, since, pr_body, previous, list_docs=True, list_deleted=True)


def deleted(block):
    return re.search(r'(?m)^deleted file mode ', block) is not None


def compose(root, base, head, since, pr_body, previous, list_docs, list_deleted=False):
    rng = '%s..%s' % (since, head) if since else '%s...%s' % (base, head)
    full_diff = git(root, 'diff', '-M', rng)
    blocks = file_blocks(full_diff)
    files = [path for path, _ in blocks]
    listed = [(path, block) for path, block in blocks if list_docs and path.endswith('.md')]
    removed = [(path, block) for path, block in blocks
               if list_deleted and deleted(block) and not (list_docs and path.endswith('.md'))]
    hidden = {path for path, _ in listed + removed}
    shown = [(path, block) for path, block in blocks if path not in hidden]
    diff_text = changed_lines('\n'.join(block for _, block in shown))
    log_text = git(root, 'log', '--no-merges', '--format=%B', rng.replace('...', '..'))
    subjects = git(root, 'log', '--no-merges', '--format=%s', rng.replace('...', '..'))
    pr_body = without_comments(pr_body)
    decisions = read_at(root, head, 'docs/decisions.md')
    agents = read_at(root, head, 'AGENTS.md')

    out = [INSTRUCTIONS]
    if listed:
        out.append('## 크기 상한(결정 packet-size-cap)\n\n묶음이 %dKB를 넘어 문서 diff %d개를 아래 목록으로 바꿨다. '
                   '문서 diff의 결정·R 번호는 세지 않았다. 필요하면 저장소에서 연다.\n' % (SIZE_CAP // 1000, len(listed)))
    if removed:
        out.append('## 입력 한도(결정 packet-deleted-files)\n\n문서를 목록으로 바꾼 뒤에도 묶음이 리뷰어 입력 한도(%d자)를 넘어 '
                   '통째로 지운 파일 %d개의 diff를 아래 목록으로 바꿨다. 지운 내용은 기준 커밋에서 연다.\n' % (INPUT_CAP, len(removed)))
    out.extend(['## AGENTS.md 3절\n', section(agents, '## 3. ')])
    numbers = decision_keys(decisions, pr_body, log_text, diff_text)
    own = own_decisions(pr_body, subjects, decisions)
    out.append('## 이 PR의 결정(원문): %s\n' % (', '.join(map(str, own)) or '없음'))
    out.extend(decision_sections(decisions, own))
    others = [n for n in numbers if n not in own]
    if others:
        out.append('## 그 밖에 언급된 결정(제목만, 필요하면 docs/decisions.md에서 연다)\n')
        out.append('\n'.join(decision_titles(decisions, others)) + '\n')
    refs = requirement_numbers(pr_body, log_text, diff_text)
    classes = changed_classes(files)
    for module in modules(files):
        path = 'specs/%s/requirements.md' % module
        requirements = read_at(root, head, path)
        if requirements:
            full, titles = requirement_sections(requirements, refs, classes)
            if full or titles:
                out.append('## 요구사항: %s\n' % module)
                out.extend(full)
            if titles:
                out.append('바뀐 클래스가 나오는 다른 절(제목만, 필요하면 %s에서 연다):\n%s\n'
                           % (path, '\n'.join('- ' + t for t in titles)))
    if previous:
        out.append('## 이전 리뷰 지적 (%s 이후 차분만 본다)\n' % since)
        out.append(previous.rstrip() + '\n')
    out.append('## diff (%s)\n' % rng)
    for _, block in sorted(shown, key=lambda b: (group(b[0]), b[0])):
        out.append('```diff\n' + block + '\n```\n')
    if listed:
        out.append('## 목록으로 바꾼 문서 diff\n')
        out.append('\n'.join('- %s (+%d -%d)' % ((path,) + counts(block)) for path, block in sorted(listed)) + '\n')
    if removed:
        out.append('## 목록으로 바꾼 삭제 파일\n')
        out.append('\n'.join('- %s (+%d -%d)' % ((path,) + counts(block)) for path, block in sorted(removed)) + '\n')
    return '\n'.join(out), files, own


def main(argv=None):
    parser = argparse.ArgumentParser(description='결정 145: Codex 리뷰 묶음 생성')
    parser.add_argument('--root', default='.')
    parser.add_argument('--base', default='origin/main')
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--since', help='재리뷰: 이미 리뷰한 커밋. 그 뒤 차분만 묶는다')
    parser.add_argument('--pr-body', help='PR 본문 파일(gh pr view --json body --jq .body)')
    parser.add_argument('--previous', help='재리뷰: 이전 지적 목록 파일')
    parser.add_argument('--out', required=True)
    args = parser.parse_args(argv)
    if args.previous and not args.since:
        parser.error('--previous는 --since와 함께 쓴다')
    root = Path(args.root).resolve()
    body = Path(args.pr_body).read_text(encoding='utf-8') if args.pr_body else ''
    previous = Path(args.previous).read_text(encoding='utf-8') if args.previous else ''
    packet, files, numbers = build(root, args.base, args.head, args.since, body, previous)
    Path(args.out).write_text(packet, encoding='utf-8')
    print('리뷰 묶음 %s: %d bytes, 파일 %d개, 결정 %s' % (args.out, len(packet.encode('utf-8')), len(files),
                                                   ', '.join(map(str, numbers)) or '없음'), file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
