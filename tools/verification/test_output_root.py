from pathlib import Path
import tempfile
import unittest

from support.output_root import output_root


class OutputRootTest(unittest.TestCase):
    """결정 verification-output-root: 기본은 저장소의 runs/, ESD_SCRATCH가 있으면 그 위치."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)

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

    def test_old_kiro_variable_is_ignored(self):
        self.assertEqual(self.repo / 'runs', output_root(self.repo, {'KIROCREW_SCRATCH': str(self.repo / 'old')}))


if __name__ == '__main__':
    unittest.main()
