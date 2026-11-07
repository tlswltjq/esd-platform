from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import decision_numbers

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/ci/decision_numbers.py'
VALID = '<a id="one"></a>\n## 1. 첫째\n<a id="two"></a>\n## 2. 둘째\n'


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write('docs/decisions.md', VALID)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def errors(self):
        return '\n'.join(decision_numbers.check(self.root)[0])


class DecisionNumbersTest(Fixture):
    def test_valid(self):
        self.write('.github/example.md', '[결정 2](../docs/decisions.md#two)')
        errors, counts = decision_numbers.check(self.root)
        self.assertEqual(errors, [])
        self.assertEqual(counts, (2, 2, 2, 1))

    def test_duplicate_number(self):
        self.write('docs/decisions.md', VALID + '## 2. 중복\n')
        self.assertIn('중복 번호 2', self.errors())

    def test_missing_number(self):
        self.write('docs/decisions.md', VALID.replace('## 2.', '## 3.'))
        self.assertIn('누락 번호 2', self.errors())

    def test_reverse_number(self):
        self.write('docs/decisions.md', '## 1. A\n## 3. C\n## 2. B\n')
        self.assertIn('역순 번호 2', self.errors())

    def test_sequence_starts_at_one(self):
        self.write('docs/decisions.md', '## 0. 잘못된 시작\n## 1. 첫째\n')
        self.assertIn('순서 오류 번호 0 (기대 1)', self.errors())

    def test_duplicate_anchor(self):
        self.write('docs/decisions.md', VALID + '<a id="one"></a>\n')
        self.assertIn('중복 앵커 one', self.errors())

    def test_other_file_mismatch(self):
        self.write('.github/example.md', '\n[결정 1](../docs/decisions.md#two)')
        error = self.errors()
        self.assertIn('.github/example.md:2', error)
        self.assertIn('[결정 1](../docs/decisions.md#two)', error)
        self.assertIn('실제 번호 2', error)

    def test_local_mismatch(self):
        self.write('docs/decisions.md', VALID + '[결정 1](#two)')
        self.assertIn('실제 번호 2', self.errors())

    def test_automatic_anchor_number_is_checked(self):
        self.write('README.md', '[결정 99](docs/decisions.md#2-둘째)')
        self.assertIn('실제 번호 2', self.errors())

    def test_fences_comments_and_inline_code_are_ignored(self):
        self.write('docs/decisions.md', VALID + '\n```md\n## 99. 예\n```\n~~~\n## 88. 예\n~~~\n<!-- 예시 -->\n`[결정 1](#two)`\n')
        self.assertEqual(decision_numbers.check(self.root), ([], (2, 2, 2, 0)))

    def test_excluded_directories(self):
        for folder in ('build', 'node_modules', '.git', 'nested/build'):
            self.write(folder + '/example.md', '[결정 1](../docs/decisions.md#two)')
        self.assertEqual(self.errors(), '')

    def test_cli_success(self):
        result = self.cli()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('통과: 헤딩 2개, 최대 번호 2, 앵커 2개, 검사한 링크 0개', result.stdout)

    def test_cli_failure(self):
        self.write('docs/decisions.md', VALID + '## 2. 중복\n')
        result = self.cli()
        self.assertEqual(result.returncode, 1)
        self.assertIn('중복 번호 2', result.stdout)

    def cli(self):
        return subprocess.run([sys.executable, '-I', '-B', str(SCRIPT), '--root', str(self.root)],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)


class RepositoryTest(unittest.TestCase):
    def test_repository_passes(self):
        errors, counts = decision_numbers.check(ROOT)
        self.assertEqual(errors, [])
        self.assertGreaterEqual(counts[0], 114)


class ReviewFindingsTest(Fixture):
    def test_missing_explicit_anchor(self):
        self.write('README.md', '[결정 2](docs/decisions.md#gone)')
        self.assertIn('없는 앵커 gone', self.errors())

    def test_compound_labels(self):
        for label in ('1·2', '1~2', '1, 2'):
            self.write('README.md', '[결정 ' + label + '](docs/decisions.md#two)')
            self.assertEqual(decision_numbers.check(self.root), ([], (2, 2, 2, 1)))
        self.write('README.md', '[결정 3~5](docs/decisions.md#two)')
        self.assertIn('실제 번호 2', self.errors())

    def test_base_numbers_and_anchors_are_immutable(self):
        self.write('docs/decisions.md', VALID.replace('## 2.', '## 3.'))
        self.assertIn('기준 판', '\n'.join(decision_numbers.check(self.root, base_text=VALID)[0]))
        self.write('docs/decisions.md', VALID.replace('id="two"', 'id="gone"'))
        self.assertIn('기준 판', '\n'.join(decision_numbers.check(self.root, base_text=VALID)[0]))

    def test_duplicate_diagnostic_and_local_sequence(self):
        self.write('docs/decisions.md', VALID + '<a id="three"></a>\n## 2. 충돌\n## 3. 다음\n')
        error = self.errors()
        self.assertIn('two', error)
        self.assertIn('three', error)
        self.assertIn('나중에 병합하는 쪽', error)
        self.assertEqual(error.count('순서 오류'), 1)

    def test_unpaired_backticks_do_not_hide_headings(self):
        self.write('docs/decisions.md', '`열림\n' + VALID + '`닫힘\n')
        self.assertEqual(self.errors(), '')

    def test_hidden_raw_heading_is_an_error(self):
        self.write('docs/decisions.md', VALID + '<!--\n## 99. 숨김\n-->')
        self.assertIn('헤딩이 지워짐', self.errors())

    def test_unclosed_inline_comment_does_not_eat_file(self):
        self.write('docs/decisions.md', '문장 <!-- 닫힘 없음\n' + VALID)
        self.assertEqual(self.errors(), '')

    def test_unclosed_block_comment_has_safety_net(self):
        self.write('docs/decisions.md', VALID + '<!--\n## 99. 숨김\n')
        self.assertIn('헤딩이 지워짐', self.errors())

    def test_comment_fence_does_not_open_code(self):
        self.write('docs/decisions.md', '<!--\n```\n-->\n' + VALID)
        self.assertEqual(decision_numbers.check(self.root)[1][0], 2)

    def test_malformed_headings(self):
        for heading in ('## 2 중복', '## 2.중복', '##\t2. 중복', '## 2.'):
            self.write('docs/decisions.md', VALID + heading)
            self.assertIn('헤딩 형식', self.errors())

    def test_multiple_and_trailing_anchors(self):
        self.write('docs/decisions.md', '<a id="one"></a><a id="alias"></a>\n## 1. 첫째\n## 2. 둘째 <a id="two"></a>\n[결정 1](#alias) [결정 2](#two)')
        self.assertEqual(decision_numbers.check(self.root), ([], (2, 2, 3, 2)))

    def test_anchor_body_resets_association(self):
        self.write('docs/decisions.md', '<a id="stray"></a>\n본문\n' + VALID + '[결정 1](#stray)')
        self.assertIn('없는 앵커 stray', self.errors())

    def test_non_decision_target_is_ignored(self):
        self.write('README.md', '[결정 99](other.md#two)')
        self.assertEqual(self.errors(), '')

    def test_other_document_local_fragment_is_ignored(self):
        self.write('README.md', '[결정 99](#two)')
        self.assertEqual(self.errors(), '')

    def test_fence_close_guards(self):
        for wrong in ('```', '~~~~', '````info'):
            self.write('docs/decisions.md', VALID + '\n````md\n' + wrong + '\n## 99. 코드\n````\n')
            self.assertEqual(decision_numbers.check(self.root)[1][0], 2)
        self.write('docs/decisions.md', '```md\n예\n```\n' + VALID)
        self.assertEqual(decision_numbers.check(self.root)[1][0], 2)

    def test_heading_indentation(self):
        for indent in range(4):
            self.write('docs/decisions.md', ' ' * indent + '## 1. 제목\n')
            self.assertEqual(decision_numbers.check(self.root)[1][0], 1)
        self.write('docs/decisions.md', VALID + '    ## 99. 코드\n')
        self.assertEqual(decision_numbers.check(self.root)[1][0], 2)

    def test_symlink_is_excluded(self):
        self.write('bad.txt', '[결정 99](docs/decisions.md#two)')
        (self.root / 'link.md').symlink_to(self.root / 'bad.txt')
        self.assertEqual(self.errors(), '')

    def test_git_tracked_build_and_untracked_file(self):
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.write('build/tracked.md', '[결정 99](../docs/decisions.md#two)')
        self.write('untracked.md', '[결정 99](docs/decisions.md#one)')
        subprocess.run(['git', '-C', str(self.root), 'add', '-f', 'docs/decisions.md', 'build/tracked.md'], check=True)
        error = self.errors()
        self.assertIn('build/tracked.md', error)
        self.assertNotIn('untracked.md', error)

    def test_missing_base_warns_but_checks_current(self):
        result = subprocess.run([sys.executable, '-I', '-B', str(SCRIPT), '--root', str(self.root), '--base-ref', 'missing'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn('경고', result.stdout)


class DecisionWorkflowTest(unittest.TestCase):
    def test_review_gate_contract(self):
        text = (ROOT / '.github/workflows/review-gate.yml').read_text()
        self.assertIn('fetch-depth: 2', text)
        self.assertIn("-p 'test_*.py'", text)
        block = text.split('- name: Check decision numbers')[1].split('- name:')[0]
        self.assertIn('if: ${{ !cancelled() }}', block)
        self.assertIn('--base-ref HEAD^1', block)

    def test_build_contract(self):
        text = (ROOT / '.github/workflows/ci.yml').read_text().split('\n  stack:')[0]
        self.assertIn('fetch-depth: 2', text)
        self.assertLess(text.index('- name: Build'), text.index('- name: Check decision numbers'))
        block = text.split('- name: Check decision numbers')[1].split('- name:')[0]
        self.assertIn('if: ${{ !cancelled() }}', block)
        self.assertIn('--base-ref HEAD^1', block)


if __name__ == '__main__':
    unittest.main()
