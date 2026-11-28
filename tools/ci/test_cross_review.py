import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

import cross_review

THREAD = '01a2206b-a434-7493-a311-12a515815057'


def session_lines(model='gpt-6.1-terra', effort='high', sandbox='read-only', turns=1):
    lines = [{'type': 'session_meta', 'payload': {'id': THREAD, 'originator': 'codex_exec'}}]
    for _ in range(turns):
        lines.append({'type': 'turn_context', 'payload': {'model': model, 'effort': effort,
                                                          'sandbox_policy': {'type': sandbox}}})
    return [json.dumps(line) for line in lines]


def write_session(sessions, lines, thread=THREAD):
    directory = Path(sessions) / '2026' / '11' / '28'
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / ('rollout-2026-11-28T13-30-55-' + thread + '.jsonl')
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return path


def fake_codex(sessions, lines, answer='지적 없음', thread=THREAD):
    """테스트용 'codex exec': 세션 기록을 쓰고, --json 이벤트를 내고, -o 파일에 답을 쓴다."""
    script = (
        'import json, sys, pathlib\n'
        'args = sys.argv[1:]\n'
        'sys.stdin.read()\n'
        'out = args[args.index("-o") + 1]\n'
        'pathlib.Path(out).write_text({answer!r}, encoding="utf-8")\n'
        'd = pathlib.Path({sessions!r}) / "2026" / "11" / "28"\n'
        'd.mkdir(parents=True, exist_ok=True)\n'
        '(d / ("rollout-x-" + {thread!r} + ".jsonl")).write_text({body!r}, encoding="utf-8")\n'
        'print(json.dumps({{"type": "thread.started", "thread_id": {thread!r}}}))\n'
        'print(json.dumps({{"type": "turn.completed"}}))\n'
    ).format(answer=answer, sessions=str(sessions), thread=thread, body='\n'.join(lines) + '\n')
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
    def run_main(self, codex, sessions, root, out):
        packet = Path(root) / 'packet.md'
        packet.write_text('# 리뷰 묶음\n', encoding='utf-8')
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = cross_review.main(['run', '--packet', str(packet), '--out', str(out), '--root', str(root),
                                      '--sessions', str(sessions), '--codex', json.dumps(codex)])
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

    def test_codex_failure_is_failure(self):
        with tempfile.TemporaryDirectory() as sessions, tempfile.TemporaryDirectory() as root:
            code, printed = self.run_main([sys.executable, '-c', 'import sys; sys.exit(3)'], sessions, root,
                                          Path(root) / 'review.md')
            self.assertEqual(1, code)
            self.assertIn('실패', printed)


class VerifyTest(unittest.TestCase):
    def test_verify_existing_session(self):
        with tempfile.TemporaryDirectory() as sessions:
            write_session(sessions, session_lines())
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = cross_review.main(['verify', '--session', THREAD, '--sessions', sessions])
            self.assertEqual(0, code)
            self.assertIn('세션 ' + THREAD, buffer.getvalue())


if __name__ == '__main__':
    unittest.main()
