import os
from pathlib import Path
import tempfile
import unittest

from support.boot_jars import application_jar


class BootJarsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=os.environ['KIROCREW_SCRATCH'])
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.libs = self.root / 'apps/order/build/libs'
        self.libs.mkdir(parents=True)

    def test_excludes_test_fixture_and_plain_jars(self):
        for name in ['order-test-fixtures.jar', 'order-plain.jar', 'order.jar']:
            (self.libs / name).touch()
        self.assertEqual(self.libs / 'order.jar', application_jar(self.root, 'order'))

    def test_missing_artifact_fails_explicitly(self):
        (self.libs / 'order-test-fixtures.jar').touch()
        with self.assertRaises(RuntimeError):
            application_jar(self.root, 'order')

    def test_multiple_versions_fail_instead_of_selecting_arbitrarily(self):
        for name in ['order-v1.jar', 'order-v2.jar']:
            (self.libs / name).touch()
        with self.assertRaises(RuntimeError):
            application_jar(self.root, 'order')


if __name__ == '__main__':
    unittest.main()
