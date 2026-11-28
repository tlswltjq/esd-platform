"""결정 review-gate-reviewed-commit: 리뷰한 커밋 뒤에 PR이 바꾼 코드 파일이 달라졌으면 잡는다."""
from pathlib import Path
import io
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout

import reviewed_commit


def body(*lines):
    return '## 검증\n\n- 리뷰한 커밋: 0000000\n\n## 리뷰 판정\n\n' + '\n'.join(lines) + '\n\n| 지적 | 판정 | 이유 |\n|---|---|---|\n' \
           '| 지적 없음 (리뷰어: Codex) | — | — |\n\n## 남은 범위\n\n- 리뷰한 커밋: 1111111\n'


class CitedTest(unittest.TestCase):
    def test_reads_the_last_commit_cited_inside_the_review_section_only(self):
        text = body('- 1차: 리뷰어: Codex, 세션 a · 리뷰한 커밋: `abcdef1`', '- 재리뷰 1: 리뷰한 커밋: ABCDEF1234567')
        self.assertEqual('abcdef1234567', reviewed_commit.cited(text))

    def test_missing_or_commented_citation_is_none(self):
        self.assertIsNone(reviewed_commit.cited(body('- 1차: 리뷰어: Codex')))
        self.assertIsNone(reviewed_commit.cited(body('<!-- - 리뷰한 커밋: abcdef1 -->')))
        self.assertIsNone(reviewed_commit.cited('본문에 절이 없다\n- 리뷰한 커밋: abcdef1\n'))


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.git('init', '-q')
        self.git('symbolic-ref', 'HEAD', 'refs/heads/main')
        self.git('config', 'user.email', 't@example.com')
        self.git('config', 'user.name', 't')
        self.write('apps/a/A.java', 'class A {}\n')
        self.write('apps/b/B.java', 'class B {}\n')
        self.write('docs/x.md', 'x\n')
        self.commit('base')
        self.git('checkout', '-q', '-b', 'pr')
        self.write('apps/a/A.java', 'class A { int v; }\n')
        self.reviewed = self.commit('feat: 리뷰받은 변경')

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-q', '-m', message)
        return self.git('rev-parse', 'HEAD').strip()

    def problems(self, reviewed=None):
        return reviewed_commit.problems(self.root, 'main', 'pr', reviewed or self.reviewed)

    def test_the_reviewed_head_passes(self):
        self.assertEqual([], self.problems())

    def test_a_code_change_after_the_review_is_reported(self):
        self.write('apps/a/A.java', 'class A { int v; int w; }\n')
        self.write('apps/c/C.java', 'class C {}\n')
        self.commit('fix: 리뷰 뒤 수정')
        text = ' '.join(self.problems())
        self.assertIn('apps/a/A.java', text)
        self.assertIn('apps/c/C.java', text)

    def test_a_mode_change_after_the_review_is_reported(self):
        # 리뷰 1 R2: 실행 권한만 바뀌면 blob은 같다. 트리의 파일 모드도 비교한다.
        (self.root / 'apps/a/A.java').chmod(0o755)
        self.commit('chore: 실행 권한')
        self.assertIn('apps/a/A.java', ' '.join(self.problems()))

    def test_document_changes_after_the_review_do_not_count(self):
        self.write('docs/x.md', 'y\n')
        self.write('docs/evidence.d/1.md', 'row\n')
        self.commit('docs: 근거')
        self.assertEqual([], self.problems())

    def test_files_only_main_changed_come_in_by_merge_without_a_rereview(self):
        self.git('checkout', '-q', 'main')
        self.write('apps/b/B.java', 'class B { int fromMain; }\n')
        self.commit('main: 다른 파일')
        self.git('checkout', '-q', 'pr')
        self.git('merge', '-q', '--no-edit', 'main')
        self.assertEqual([], self.problems())

    def test_a_merge_that_changes_a_file_of_the_pull_request_needs_a_rereview(self):
        self.git('checkout', '-q', 'main')
        self.write('apps/a/A.java', 'class A { int fromMain; }\n')
        self.commit('main: 같은 파일')
        self.git('checkout', '-q', 'pr')
        subprocess.run(['git', '-C', str(self.root), 'merge', '-q', '--no-edit', 'main'], capture_output=True)
        self.write('apps/a/A.java', 'class A { int v; int fromMain; }\n')
        self.commit('merge: 충돌 해소')
        self.assertIn('apps/a/A.java', ' '.join(self.problems()))

    def test_a_reverted_file_is_no_longer_part_of_the_pull_request(self):
        self.write('apps/a/A.java', 'class A {}\n')
        self.write('docs/x.md', 'z\n')
        self.commit('revert')
        self.assertEqual([], self.problems())

    def test_an_unknown_or_foreign_commit_is_rejected(self):
        self.assertIn('찾을 수 없다', ' '.join(self.problems('deadbeef')))
        self.git('checkout', '-q', 'main')
        self.write('apps/b/B.java', 'class B { int other; }\n')
        foreign = self.commit('main: PR 밖 커밋')
        self.assertIn('PR 브랜치의 커밋이 아니다', ' '.join(self.problems(foreign)))

    def test_main_reports_a_missing_citation_and_skips_document_only_pull_requests(self):
        review = self.root / 'body.md'
        review.write_text(body('- 1차: 리뷰어: Codex'), encoding='utf-8')
        out = io.StringIO()
        with redirect_stdout(out):
            code = reviewed_commit.main(['--body', str(review), '--root', str(self.root), '--base', 'main', '--head', 'pr'])
        self.assertEqual(1, code)
        self.assertIn('리뷰한 커밋', out.getvalue())
        review.write_text(body('- 1차: 리뷰한 커밋: ' + self.reviewed), encoding='utf-8')
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, reviewed_commit.main(['--body', str(review), '--root', str(self.root),
                                                      '--base', 'main', '--head', 'pr']))
        self.git('checkout', '-q', '-b', 'docs-only', 'main')
        self.write('docs/x.md', 'only docs\n')
        self.commit('docs')
        review.write_text('## 리뷰 판정\n\n', encoding='utf-8')
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, reviewed_commit.main(['--body', str(review), '--root', str(self.root),
                                                      '--base', 'main', '--head', 'docs-only']))


class WorkflowTest(unittest.TestCase):
    def test_review_gate_runs_the_check_on_full_history_with_the_body_from_the_environment(self):
        text = (Path(__file__).resolve().parents[2] / '.github/workflows/review-gate.yml').read_text(encoding='utf-8')
        block = text.split('- name: Check reviewed commit')[1]
        self.assertIn('if: ${{ !cancelled() }}', block)
        self.assertIn('git fetch --quiet --no-tags --unshallow origin', block)
        self.assertIn("--base 'HEAD^1' --head 'HEAD^2'", block)
        for line in block.split('run: |')[1].splitlines():
            self.assertNotIn('${{', line)


if __name__ == '__main__':
    unittest.main()
