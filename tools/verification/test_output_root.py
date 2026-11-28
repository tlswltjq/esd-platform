import os
from pathlib import Path
import tempfile
import unittest

from support.output_root import output_root


class OutputRootTest(unittest.TestCase):
    """결정 verification-output-root: 기본은 저장소의 runs/, ESD_SCRATCH가 있으면 그 위치."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name).resolve()  # macOS: /var는 /private/var로 풀린다

    def test_defaults_to_repository_runs(self):
        root = output_root(self.repo, {})
        self.assertEqual(self.repo / 'runs', root)
        self.assertTrue(root.is_dir())

    def test_empty_variable_is_the_default(self):
        self.assertEqual(self.repo / 'runs', output_root(self.repo, {'ESD_SCRATCH': ''}))

    def test_esd_scratch_overrides(self):
        target = self.repo / 'elsewhere/nested'
        self.assertEqual(target, output_root(self.repo, {'ESD_SCRATCH': str(target)}))
        self.assertTrue(target.is_dir())

    def test_r3_relative_scratch_becomes_absolute(self):
        # PR #259 R3: 상대 경로를 주고 다른 디렉터리에서 실행해도 Compose가 같은 위치를 본다.
        previous = os.getcwd()
        os.chdir(self.directory.name)
        self.addCleanup(os.chdir, previous)
        root = output_root(self.repo, {'ESD_SCRATCH': 'artifacts'})
        self.assertTrue(root.is_absolute())
        self.assertEqual((Path(self.directory.name) / 'artifacts').resolve(), root)
        self.assertTrue(output_root('relative-repo', {}).is_absolute())

    def test_old_kiro_variable_is_ignored(self):
        self.assertEqual(self.repo / 'runs', output_root(self.repo, {'KIROCREW_SCRATCH': str(self.repo / 'old')}))


if __name__ == '__main__':
    unittest.main()
