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

    def test_payment_retry_delay_outside_the_payment_range_is_refused(self):
        # Payment refuses retry-delay-ms outside 100..300000 at startup; fail before booting a stack (decision 138).
        for value in (99, 300001):
            with self.assertRaisesRegex(ValueError, "payment_retry_ms"):
                FakePortOneStack(Path(__file__).resolve().parents[2], "test-fake-portone-stack",
                                 offset_env="FAKE_PORTONE_STACK_TEST_PORT_OFFSET", prefix="fake-portone-stack-",
                                 webhooks=True, payment_retry_ms=value)


if __name__ == "__main__":
    unittest.main()
