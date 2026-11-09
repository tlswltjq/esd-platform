import unittest
from pathlib import Path

from support.fake_portone_stack import FakePortOneStack


class FakePortOneStackOptionsTest(unittest.TestCase):
    def test_settlement_container_and_tracing_are_refused_together(self):
        # The Settlement container gets no trace export settings, so a combined run would verify a partial trace.
        with self.assertRaisesRegex(ValueError, "cannot be combined"):
            FakePortOneStack(Path(__file__).resolve().parents[2], "test-fake-portone-stack",
                             offset_env="FAKE_PORTONE_STACK_TEST_PORT_OFFSET", prefix="fake-portone-stack-",
                             settlement_compose=True, tracing=True)


if __name__ == "__main__":
    unittest.main()
