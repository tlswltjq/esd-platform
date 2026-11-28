#!/usr/bin/env python3
"""결정 237: Codex 교차 리뷰를 `codex exec`로 직접 부르고, 세션 기록의 실제 모델·추론 수준을 확인한다.

결정 108·117·145는 코드 PR을 구현과 다른 모델(Codex)이 추론 수준 high, 읽기 전용으로 리뷰하게 한다.
요청한 값과 실제로 돈 값이 다를 수 있으므로(2026-11-28: `kirocrew spawn --model gpt-6.1-terra`가
Claude로 돌았고, `auto` 요청은 추론 수준 [low]로 풀렸다), 리뷰가 끝나면 Codex 세션 기록
(`~/.codex/sessions/**/rollout-*-<thread>.jsonl`)의 모든 `turn_context`를 읽어 확인한다.
그 세션의 사용자 입력에 이번 리뷰 묶음 원문이 있어야 하고(시험 호출·다른 작업 세션으로 리뷰어 줄을 받지 않는다),
세션이 끝난 기록(`task_complete`)의 마지막 답이 결과 파일과 같아야 한다(중간에 끊긴 세션·손으로 쓴 결과를 막는다).
확인에 실패하면 결과 파일을 `<out>.unverified`로 옮기고 실패로 끝난다. 그 결과는 리뷰로 쓰지 않는다.

  # 리뷰 묶음(review_packet.py)을 넣어 리뷰를 받는다. 통과하면 PR 본문에 적을 리뷰어 줄을 출력한다.
  python3 tools/ci/cross_review.py run --packet <묶음> --out <결과.md> [--root <worktree>]
  # 이미 있는 세션을 확인한다.
  python3 tools/ci/cross_review.py verify --session <thread id> --packet <그 세션에 넣은 묶음> --result <그 세션의 결과>
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

MODEL = 'gpt-6.1-terra'
EFFORT = 'high'
SANDBOX = 'read-only'
SESSIONS = Path.home() / '.codex' / 'sessions'
FALLBACK_CODEX = Path.home() / '.npm-packages' / 'bin' / 'codex'


def command(codex, model, root, out):
    return [*codex, 'exec', '-m', model, '-c', 'model_reasoning_effort="' + EFFORT + '"',
            '-s', SANDBOX, '-C', str(root), '--json', '-o', str(out), '-']


def thread_id(event_lines):
    for line in event_lines:
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if isinstance(event, dict) and event.get('type') == 'thread.started' and event.get('thread_id'):
            return event['thread_id']
    return None


def find_session(sessions, thread):
    matches = sorted(Path(sessions).glob('**/rollout-*' + thread + '.jsonl'))
    return matches[-1] if matches else None


def check(session_lines, model):
    """세션의 모든 turn_context가 요청한 모델·추론 수준·샌드박스인지 본다. 어긋난 이유 목록을 돌려준다."""
    errors = []
    turns = 0
    for line in session_lines:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if not isinstance(record, dict) or record.get('type') != 'turn_context':
            continue
        turns += 1
        payload = record.get('payload') or {}
        sandbox = (payload.get('sandbox_policy') or {}).get('type')
        actual = (payload.get('model'), payload.get('effort'), sandbox)
        if actual != (model, EFFORT, SANDBOX):
            errors.append('턴 {}: 모델 {}, 추론 {}, 샌드박스 {} (기대: {}, {}, {})'.format(
                turns, actual[0], actual[1], actual[2], model, EFFORT, SANDBOX))
    if turns == 0:
        errors.append('세션 기록에 turn_context가 없다')
    return errors


def records(session_lines):
    for line in session_lines:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if isinstance(record, dict) and isinstance(record.get('payload'), dict):
            yield record


def carries_packet(record, wanted):
    payload = record['payload']
    if record.get('type') != 'response_item' or payload.get('type') != 'message' or payload.get('role') != 'user':
        return False
    return any(isinstance(part, dict) and wanted in (part.get('text') or '') for part in payload.get('content') or [])


def packet_errors(session_lines, packet):
    """세션의 사용자 메시지 가운데 리뷰 묶음 원문을 담은 것이 있는지 본다."""
    wanted = packet.strip()
    if not wanted:
        return ['리뷰 묶음이 비어 있다']
    if any(carries_packet(record, wanted) for record in records(session_lines)):
        return []
    return ['세션의 사용자 입력에 이번 리뷰 묶음이 없다']


def completion_errors(session_lines, result, packet):
    """리뷰 묶음이 (마지막으로) 들어간 뒤 세션이 끝났고(task_complete), 그 마지막 답이 결과 파일과 같은지 본다.

    묶음보다 앞서 끝난 다른 작업의 답은 세지 않는다(PR #257 재리뷰 2 R1).
    """
    wanted = packet.strip()
    answers = None
    for record in records(session_lines):
        if wanted and carries_packet(record, wanted):
            answers = []
        elif answers is not None and record.get('type') == 'event_msg' \
                and record['payload'].get('type') == 'task_complete':
            answers.append((record['payload'].get('last_agent_message') or '').strip())
    if not answers:
        return ['리뷰 묶음 뒤에 세션이 끝난 기록(task_complete)이 없다']
    if answers[-1] != result.strip():
        return ['세션의 마지막 답이 결과 파일과 다르다']
    return []


def reviewer_line(model, thread):
    return '리뷰어: Codex {}, 추론 {}, {}, 세션 {}'.format(model, EFFORT, SANDBOX, thread)


def verify(sessions, thread, model, packet, result):
    path = find_session(sessions, thread)
    if path is None:
        return ['세션 기록을 찾지 못했다: ' + thread + ' (' + str(sessions) + ')']
    lines = path.read_text(encoding='utf-8').splitlines()
    return check(lines, model) + packet_errors(lines, packet) + completion_errors(lines, result, packet)


def default_codex():
    found = shutil.which('codex')
    if found:
        return [found]
    if FALLBACK_CODEX.exists():
        return [str(FALLBACK_CODEX)]
    return ['codex']


def fail(reasons, out=None):
    if out is not None and out.exists():
        os.replace(str(out), str(out) + '.unverified')
    print('실패: Codex 교차 리뷰를 확인하지 못했다(결정 237). 이 결과는 리뷰로 쓰지 않는다.')
    for reason in reasons:
        print('  - ' + reason)
    return 1


def run(args):
    out = Path(args.out)
    if out.exists():
        out.unlink()
    codex = json.loads(args.codex) if args.codex else default_codex()
    packet = Path(args.packet).read_text(encoding='utf-8')
    try:
        completed = subprocess.run(command(codex, args.model, args.root, out), input=packet,
                                   capture_output=True, text=True, timeout=args.timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        return fail(['codex exec 실행: ' + str(error)], out)
    thread = thread_id(completed.stdout.splitlines())
    reasons = []
    if completed.returncode != 0:
        reasons.append('codex exec 종료 코드 ' + str(completed.returncode) + ': ' + completed.stderr.strip()[-500:])
    if thread is None:
        reasons.append('--json 출력에 thread.started가 없다')
    if not out.exists():
        reasons.append('결과 파일이 없다: ' + str(out))
    elif thread is not None:
        reasons.extend(verify(args.sessions, thread, args.model, packet, out.read_text(encoding='utf-8')))
    if reasons:
        return fail(reasons, out)
    print('통과: ' + reviewer_line(args.model, thread))
    print('결과: ' + str(out))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description='결정 237: Codex 교차 리뷰 호출과 확인')
    commands = parser.add_subparsers(dest='action', required=True)
    run_parser = commands.add_parser('run', help='리뷰 묶음을 넣어 codex exec를 부르고 세션을 확인한다')
    run_parser.add_argument('--packet', required=True, help='review_packet.py가 만든 리뷰 묶음')
    run_parser.add_argument('--out', required=True, help='Codex의 마지막 답을 쓸 파일')
    run_parser.add_argument('--root', default='.', help='Codex 작업 디렉터리(리뷰할 worktree)')
    run_parser.add_argument('--timeout', type=int, default=3600)
    verify_parser = commands.add_parser('verify', help='이미 있는 세션의 모델·추론 수준·샌드박스를 확인한다')
    verify_parser.add_argument('--session', required=True, help='thread id')
    verify_parser.add_argument('--packet', required=True, help='그 세션에 넣은 리뷰 묶음')
    verify_parser.add_argument('--result', required=True, help='그 세션의 결과(마지막 답) 파일')
    for sub in (run_parser, verify_parser):
        sub.add_argument('--model', default=MODEL)
        sub.add_argument('--sessions', default=str(SESSIONS), help='Codex 세션 기록 디렉터리')
        sub.add_argument('--codex', help='codex 명령(JSON 배열). 기본은 PATH 또는 ~/.npm-packages/bin/codex')
    args = parser.parse_args(argv)
    if args.action == 'verify':
        reasons = verify(args.sessions, args.session, args.model, Path(args.packet).read_text(encoding='utf-8'),
                         Path(args.result).read_text(encoding='utf-8'))
        if reasons:
            return fail(reasons)
        print('통과: ' + reviewer_line(args.model, args.session))
        return 0
    return run(args)


if __name__ == '__main__':
    sys.exit(main())
