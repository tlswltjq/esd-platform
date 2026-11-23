import io
from pathlib import Path
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stdout

import gradle_result

SUITE = '<testsuite name="S" tests="{tests}" failures="{failures}" errors="0" skipped="0">{cases}</testsuite>'


def write_xml(root, module, tests=1, failures=0):
    directory = Path(root) / module / 'build' / 'test-results' / 'test'
    directory.mkdir(parents=True, exist_ok=True)
    cases = '<testcase classname="C" name="ok"/>' * (tests - failures)
    cases += '<testcase classname="C" name="bad"><failure/></testcase>' * failures
    (directory / 'TEST-C.xml').write_text(SUITE.format(tests=tests, failures=failures, cases=cases), encoding='utf-8')
    return directory


def fake_gradle(script):
    """테스트용 'Gradle': 파이썬 한 줄을 실행하는 명령."""
    return [sys.executable, '-c', script]


class VerdictTest(unittest.TestCase):
    def test_exit_code_and_fresh_xml_and_no_failures(self):
        ok = {'files': 1, 'tests': 3, 'failures': 0, 'errors': 0, 'skipped': 0, 'failed': []}
        self.assertTrue(gradle_result.verdict(0, ok)[0])
        self.assertFalse(gradle_result.verdict(1, ok)[0])
        self.assertFalse(gradle_result.verdict(0, dict(ok, files=0))[0])
        self.assertFalse(gradle_result.verdict(0, dict(ok, failures=1))[0])


class RunTest(unittest.TestCase):
    def test_stale_xml_from_an_earlier_run_is_not_a_pass(self):
        # PR #215: 컴파일 실패로 테스트가 돌지 않았는데 이전 XML을 통과로 읽었다.
        with tempfile.TemporaryDirectory() as root:
            write_xml(root, 'apps/order')
            compile_failure = fake_gradle('import sys; sys.exit(0)')
            out = io.StringIO()
            with redirect_stdout(out):
                code = gradle_result.main(['--root', root, '--', *compile_failure])
            self.assertEqual(1, code)
            self.assertIn('XML이 없다', out.getvalue())
            self.assertFalse((Path(root) / 'apps/order/build/test-results').exists())

    def test_fresh_passing_xml_passes_for_each_repeat(self):
        with tempfile.TemporaryDirectory() as root:
            script = ('from pathlib import Path; d=Path("m/build/test-results/test"); d.mkdir(parents=True);'
                      '(d/"TEST-C.xml").write_text(\'' + SUITE.format(tests=1, failures=0,
                                                                      cases='<testcase classname="C" name="ok"/>')
                      + '\')')
            out = io.StringIO()
            with redirect_stdout(out):
                code = gradle_result.main(['--root', root, '--repeat', '2', '--', *fake_gradle(script)])
            self.assertEqual(0, code, out.getvalue())
            self.assertIn('2회 모두', out.getvalue())

    def test_failures_are_listed(self):
        with tempfile.TemporaryDirectory() as root:
            started = time.time() - 1
            write_xml(root, 'm', tests=2, failures=1)
            totals = gradle_result.summarize(root, started)
            self.assertEqual(['C#bad'], totals['failed'])
            self.assertFalse(gradle_result.verdict(0, totals)[0])

    def test_nonzero_exit_fails_even_with_xml(self):
        with tempfile.TemporaryDirectory() as root:
            script = ('import sys; from pathlib import Path; d=Path("m/build/test-results/test"); d.mkdir(parents=True);'
                      '(d/"TEST-C.xml").write_text(\'' + SUITE.format(tests=1, failures=0,
                                                                      cases='<testcase classname="C" name="ok"/>')
                      + '\'); sys.exit(1)')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, gradle_result.main(['--root', root, '--', *fake_gradle(script)]))

    def test_build_cache_is_disabled_for_gradle(self):
        # PR #226 R1: 캐시에서 복원된 XML은 새 mtime이라 실행으로 보인다.
        self.assertEqual(['./gradlew', ':a:test', '--no-build-cache'], gradle_result.without_cache(['./gradlew', ':a:test']))
        self.assertEqual(['gradle', 'test', '--no-build-cache'], gradle_result.without_cache(['gradle', 'test', '--no-build-cache']))
        self.assertEqual(['python3', '-c', 'x'], gradle_result.without_cache(['python3', '-c', 'x']))

    def test_nested_worktrees_are_left_alone(self):
        # PR #226 R5: 하위 worktree(.git 파일)의 결과는 다른 작업의 것이다.
        with tempfile.TemporaryDirectory() as root:
            write_xml(root, 'a')
            nested = Path(root) / 'other'
            write_xml(str(nested), 'b')
            (nested / '.git').write_text('gitdir: /elsewhere\n', encoding='utf-8')
            write_xml(root, '.claude/worktrees/x')
            self.assertEqual(1, gradle_result.clear_results(root))
            self.assertTrue((nested / 'b/build/test-results').exists())
            self.assertTrue((Path(root) / '.claude/worktrees/x/build/test-results').exists())

    def test_only_build_test_results_are_removed(self):
        with tempfile.TemporaryDirectory() as root:
            write_xml(root, 'a')
            keep = Path(root) / 'a' / 'src' / 'test-results'
            keep.mkdir(parents=True)
            self.assertEqual(1, gradle_result.clear_results(root))
            self.assertTrue(keep.exists())


if __name__ == '__main__':
    unittest.main()
