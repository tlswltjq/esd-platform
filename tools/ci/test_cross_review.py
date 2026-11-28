import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

import cross_review

THREAD = '01a2206b-a434-7493-a311-12a515815057'
PACKET = '# 리뷰 묶음\n'


def session_lines(model='gpt-6.1-terra', effort='high', sandbox='read-only', turns=1, packet=PACKET,
                  answer='지적 없음', complete=True):
    lines = [{'type': 'session_meta', 'payload': {'id': THREAD, 'originator': 'codex_exec'}},
             {'type': 'event_msg', 'payload': {'type': 'task_started'}},
             {'type': 'response_item', 'payload': {'type': 'message', 'role': 'user',
                                                   'content': [{'type': 'input_text', 'text': packet}]}}]
    for _ in range(turns):
        lines.append({'type': 'turn_context', 'payload': {'model': model, 'effort': effort,
                                                          'sandbox_policy': {'type': sandbox}}})
    if complete:
        lines.append({'type': 'event_msg', 'payload': {'type': 'task_complete', 'last_agent_message': answer}})
    return [json.dumps(line) for line in lines]


def write_session(sessions, lines, thread=THREAD):
    directory = Path(sessions) / '2026' / '11' / '28'
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / ('rollout-2026-11-28T13-30-55-' + thread + '.jsonl')
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return path


def fake_codex(sessions, lines, answer='지적 없음', thread=THREAD, sleep=0):
    """테스트용 'codex exec': 세션 기록을 쓰고, --json 이벤트를 내고, -o 파일에 답을 쓴다."""
    script = (
        'import json, sys, pathlib\n'
        'args = sys.argv[1:]\n'
        'sys.stdin.read()\n'
        'out = args[args.index("-o") + 1]\n'
        'pathlib.Path(out).write_text({answer!r}, encoding="utf-8")\n'
        'import time; time.sleep({sleep!r})\n'
        'd = pathlib.Path({sessions!r}) / "2026" / "11" / "28"\n'
        'd.mkdir(parents=True, exist_ok=True)\n'
        '(d / ("rollout-x-" + {thread!r} + ".jsonl")).write_text({body!r}, encoding="utf-8")\n'
        'print(json.dumps({{"type": "thread.started", "thread_id": {thread!r}}}))\n'
        'print(json.dumps({{"type": "turn.completed"}}))\n'
    ).format(answer=answer, sessions=str(sessions), thread=thread, body='\n'.join(lines) + '\n', sleep=sleep)
    return [sys.executable, '-c', script]


class CommandTest(unittest.TestCase):
    def test_pins_model_high_effort_read_only_and_reads_packet_from_stdin(self):
        command = cross_review.command(['codex'], 'gpt-6.1-terra', '/w', '/o.md')
        self.assertEqual(['codex', 'exec', '-m', 'gpt-6.1-terra', '-c', 'model_reasoning_effort="high"',
                          '-s', 'read-only', '-C', '/w', '--json', '-o', '/o.md', '-'], command)


class ThreadIdTest(unittest.TestCase):
    def test_reads_thread_started_event(self):
        events = ['{"type":"thread.started","thread_id":"' + THREAD + '"}', 'not json', '{"type":"turn.completed"}']
        self.assertEqual(THREAD, cross_review.thread_id(events))

    def test_missing_event_is_none(self):
        self.assertIsNone(cross_review.thread_id(['{"type":"turn.completed"}']))


class CheckTest(unittest.TestCase):
    def test_pinned_model_high_read_only_passes(self):
        self.assertEqual([], cross_review.check(session_lines(turns=2), 'gpt-6.1-terra'))

    def test_other_model_low_effort_or_writable_sandbox_fails(self):
        # 2026-11-28: kirocrew spawn --model gpt-6.1-terra가 Claude로 돌았고, auto는 [low]로 풀렸다.
        self.assertTrue(cross_review.check(session_lines(model='claude-opus-5-5'), 'gpt-6.1-terra'))
        self.assertTrue(cross_review.check(session_lines(effort='low'), 'gpt-6.1-terra'))
        self.assertTrue(cross_review.check(session_lines(sandbox='workspace-write'), 'gpt-6.1-terra'))

    def test_any_later_turn_that_drifts_fails(self):
        lines = session_lines() + session_lines(effort='medium')[1:]
        self.assertTrue(cross_review.check(lines, 'gpt-6.1-terra'))

    def test_session_without_turn_context_fails(self):
        self.assertTrue(cross_review.check(session_lines(turns=0), 'gpt-6.1-terra'))


class PacketTest(unittest.TestCase):
    def test_session_must_carry_this_packet_as_user_input(self):
        # PR #257 R2: 모델·추론만 맞는 시험 호출이나 다른 작업 세션으로 리뷰어 줄을 받지 않는다.
        self.assertEqual([], cross_review.packet_errors(session_lines(), PACKET))
        self.assertTrue(cross_review.packet_errors(session_lines(packet='다른 작업'), PACKET))


class CompletionTest(unittest.TestCase):
    def test_session_must_complete_with_this_result(self):
        # PR #257 재리뷰 R1: 묶음을 넣고 답 전에 끊긴 세션으로 리뷰어 줄을 받지 않는다.
        self.assertEqual([], cross_review.completion_errors(session_lines(), '지적 없음\n', PACKET))
        self.assertTrue(cross_review.completion_errors(session_lines(complete=False), '지적 없음', PACKET))
        self.assertTrue(cross_review.completion_errors(session_lines(answer='다른 답'), '지적 없음', PACKET))

    def test_completion_before_the_packet_does_not_count(self):
        # PR #257 재리뷰 2 R1: 앞선 작업의 task_complete를 이번 묶음의 답으로 쓰지 않는다.
        earlier = json.dumps({'type': 'event_msg', 'payload': {'type': 'task_complete', 'last_agent_message': '이전 답'}})
        lines = session_lines(complete=False)
        lines.insert(2, earlier)
        self.assertTrue(cross_review.completion_errors(lines, '이전 답', PACKET))

    def test_session_with_more_than_one_task_does_not_count(self):
        # PR #257 재리뷰 3 R1: codex exec 세션은 작업 하나다. 묶음 턴이 끊긴 뒤 다른 작업이 끝나거나,
        # 리뷰 뒤 다른 턴이 이어진 세션은 어느 답이 리뷰인지 가를 수 없으므로 받지 않는다.
        later = [json.dumps({'type': 'event_msg', 'payload': {'type': 'task_started'}}),
                 json.dumps({'type': 'event_msg', 'payload': {'type': 'task_complete', 'last_agent_message': '다른 작업'}})]
        self.assertTrue(cross_review.completion_errors(session_lines(complete=False) + later, '다른 작업', PACKET))
        self.assertTrue(cross_review.completion_errors(session_lines() + later, '지적 없음', PACKET))
        self.assertTrue(cross_review.completion_errors(session_lines() + later, '다른 작업', PACKET))


class FindSessionTest(unittest.TestCase):
    def test_finds_by_thread_id_in_file_name(self):
        with tempfile.TemporaryDirectory() as sessions:
            path = write_session(sessions, session_lines())
            write_session(sessions, session_lines(), thread='other')
            self.assertEqual(path, cross_review.find_session(sessions, THREAD))

    def test_missing_is_none(self):
        with tempfile.TemporaryDirectory() as sessions:
            self.assertIsNone(cross_review.find_session(sessions, THREAD))


class RunTest(unittest.TestCase):
    def run_main(self, codex, sessions, root, out, extra=()):
        packet = Path(root) / 'packet.md'
        packet.write_text(PACKET, encoding='utf-8')
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = cross_review.main(['run', '--packet', str(packet), '--out', str(out), '--root', str(root),
                                      '--sessions', str(sessions), '--codex', json.dumps(codex), *extra])
        return code, buffer.getvalue()

    def test_verified_review_prints_reviewer_line(self):
        with tempfile.TemporaryDirectory() as sessions, tempfile.TemporaryDirectory() as root:
            out = Path(root) / 'review.md'
            code, printed = self.run_main(fake_codex(sessions, session_lines()), sessions, root, out)
            self.assertEqual(0, code)
            self.assertIn('리뷰어: Codex gpt-6.1-terra, 추론 high, read-only, 세션 ' + THREAD, printed)
            self.assertEqual('지적 없음', out.read_text(encoding='utf-8'))

    def test_unverified_review_fails_and_moves_result_aside(self):
        with tempfile.TemporaryDirectory() as sessions, tempfile.TemporaryDirectory() as root:
            out = Path(root) / 'review.md'
            code, printed = self.run_main(fake_codex(sessions, session_lines(effort='low')), sessions, root, out)
            self.assertEqual(1, code)
            self.assertIn('실패', printed)
            self.assertFalse(out.exists())
            self.assertTrue(Path(str(out) + '.unverified').exists())

    def test_session_of_another_task_fails(self):
        with tempfile.TemporaryDirectory() as sessions, tempfile.TemporaryDirectory() as root:
            out = Path(root) / 'review.md'
            code, _ = self.run_main(fake_codex(sessions, session_lines(packet='다른 작업')), sessions, root, out)
            self.assertEqual(1, code)
            self.assertFalse(out.exists())

    def test_timeout_after_writing_result_moves_it_aside(self):
        # PR #257 R1: 결과를 쓴 뒤 시간 초과되면 검증 안 된 결과가 원래 경로에 남았다.
        with tempfile.TemporaryDirectory() as sessions, tempfile.TemporaryDirectory() as root:
            out = Path(root) / 'review.md'
            code, printed = self.run_main(fake_codex(sessions, session_lines(), sleep=5), sessions, root, out,
                                          extra=('--timeout', '1'))
            self.assertEqual(1, code)
            self.assertIn('실패', printed)
            self.assertFalse(out.exists())
            self.assertTrue(Path(str(out) + '.unverified').exists())

    def test_codex_failure_is_failure(self):
        with tempfile.TemporaryDirectory() as sessions, tempfile.TemporaryDirectory() as root:
            code, printed = self.run_main([sys.executable, '-c', 'import sys; sys.exit(3)'], sessions, root,
                                          Path(root) / 'review.md')
            self.assertEqual(1, code)
            self.assertIn('실패', printed)


class VerifyTest(unittest.TestCase):
    def verify(self, sessions, packet_text, result_text='지적 없음'):
        packet = Path(sessions) / 'packet.md'
        packet.write_text(packet_text, encoding='utf-8')
        result = Path(sessions) / 'result.md'
        result.write_text(result_text, encoding='utf-8')
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = cross_review.main(['verify', '--session', THREAD, '--packet', str(packet), '--result', str(result),
                                      '--sessions', sessions])
        return code, buffer.getvalue()

    def test_verify_existing_session_with_its_packet(self):
        with tempfile.TemporaryDirectory() as sessions:
            write_session(sessions, session_lines())
            code, printed = self.verify(sessions, PACKET)
            self.assertEqual(0, code)
            self.assertIn('세션 ' + THREAD, printed)

    def test_d062_packet_with_unicode_line_separator_is_found(self):
        # D-062: Codex는 세션 JSON에 U+2028을 이스케이프하지 않고 쓴다. splitlines()로 나누면 그 줄이 쪼개져
        # 리뷰 묶음을 담은 기록을 읽지 못하고 "묶음이 없다"로 실패했다(PR #259 첫 리뷰).
        packet = '# 리뷰 묶음\n코드에 든 \u2028 문자\n'
        lines = [json.loads(line) for line in session_lines(packet=packet)]
        with tempfile.TemporaryDirectory() as sessions:
            write_session(sessions, [json.dumps(line, ensure_ascii=False) for line in lines])
            code, printed = self.verify(sessions, packet)
            self.assertEqual(0, code, printed)

    def test_verify_rejects_session_that_did_not_review_this_packet(self):
        with tempfile.TemporaryDirectory() as sessions:
            write_session(sessions, session_lines(packet='시험 호출'))
            code, printed = self.verify(sessions, PACKET)
            self.assertEqual(1, code)
            self.assertNotIn('리뷰어:', printed)

    def test_verify_rejects_unfinished_session_or_other_result(self):
        with tempfile.TemporaryDirectory() as sessions:
            write_session(sessions, session_lines(complete=False))
            self.assertEqual(1, self.verify(sessions, PACKET)[0])
        with tempfile.TemporaryDirectory() as sessions:
            write_session(sessions, session_lines())
            self.assertEqual(1, self.verify(sessions, PACKET, result_text='손으로 쓴 결과')[0])


if __name__ == '__main__':
    unittest.main()
