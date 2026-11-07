from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import decision_numbers

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/ci/decision_numbers.py'
VALID = '<a id="one"></a>\n## 1. 첫째\n<a id="two"></a>\n## 2. 둘째\n'


class DecisionNumbersTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=str(ROOT))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write('docs/decisions.md', VALID)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def errors(self):
        return '\n'.join(decision_numbers.check(self.root)[0])

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

    def test_automatic_anchor_is_skipped(self):
        self.write('README.md', '[결정 99](docs/decisions.md#2-둘째)')
        self.assertEqual(decision_numbers.check(self.root), ([], (2, 2, 2, 0)))

    def test_fences_comments_and_inline_code_are_ignored(self):
        self.write('docs/decisions.md', VALID + '\n```md\n## 99. 예\n```\n~~~\n## 88. 예\n~~~\n<!--\n## 77. 예\n-->\n`[결정 1](#two)`\n')
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


if __name__ == '__main__':
    unittest.main()
