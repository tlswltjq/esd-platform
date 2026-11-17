import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from support import messaging_measurement  # noqa: E402

GROUPS = messaging_measurement.PURCHASE_GROUPS


def broker(count):
    return {group: {"count": count, "negative": 0} for group in GROUPS}


class CommandTest(unittest.TestCase):
    def test_load_command_passes_the_offset_and_load_shape_to_the_driver(self):
        command = messaging_measurement.load_command(300, 5, 120, 20, "/tmp/load.json", 15)
        self.assertEqual(command[:2], ["./gradlew", ":e2e:messagingLoadTest"])
        for flag in ["-Dstove.e2e.port-offset=300", "-Dstove.e2e.load-rate=5",
                     "-Dstove.e2e.load-duration-secs=120", "-Dstove.e2e.load-warmup-secs=15",
                     "-Dstove.e2e.load-buyers=20", "-Dstove.e2e.load-evidence=/tmp/load.json"]:
            self.assertIn(flag, command)

    def test_collectors_wait_for_settlement_and_scope_by_run_keys(self):
        journey = messaging_measurement.journey_command("/repo", "db", "/tmp/load.json", "/tmp/j.json")
        self.assertIn("--wait-secs", journey)
        brokers = messaging_measurement.broker_command("/repo", "db", "/tmp/load.json", "/tmp/b.json")
        self.assertIn("--keys-from", brokers)
        self.assertIn("--settle-secs", brokers)


class BrokerEnvironmentTest(unittest.TestCase):
    def test_kafka_is_the_default_and_needs_no_switch(self):
        self.assertEqual(messaging_measurement.broker_environment("order", "kafka"), {})

    def test_direct_switches_messaging_apps_and_keeps_store_on_kafka(self):
        self.assertEqual(messaging_measurement.broker_environment("order", "direct"), {"STOVE_MESSAGING_BROKER": "direct"})
        self.assertEqual(messaging_measurement.broker_environment("store", "direct"), {})
        self.assertEqual(messaging_measurement.broker_environment("catalog", "direct")["STOVE_MESSAGING_DIRECT_ALSOKAFKATOPICS"],
                         "stove.catalog.v1")

    def test_unknown_broker_is_rejected(self):
        with self.assertRaises(ValueError):
            messaging_measurement.broker_environment("order", "nats")


class ValidityTest(unittest.TestCase):
    def test_a_clean_run_is_valid(self):
        measured = [{"status": 200, "lateMs": 1.0}, {"status": 200, "lateMs": 2.0}]
        result = messaging_measurement.validity(measured, {"completed": 2, "incomplete": 0, "failed": 0}, broker(2))
        self.assertTrue(result["valid"], result["reasons"])

    def test_failures_late_starts_incomplete_and_missing_consumption_invalidate_the_run(self):
        # 일부만 완료돼도 PASS가 되던 경로(PR #206 R5)와 도착률 미달(R1).
        measured = [{"status": 200, "lateMs": 500.0}, {"status": 500}]
        missing = broker(1)
        missing["order-license-grant"] = {"count": 0, "negative": 0}
        result = messaging_measurement.validity(measured, {"completed": 1, "incomplete": 1, "failed": 1}, missing)
        self.assertFalse(result["valid"])
        text = " ".join(result["reasons"])
        for expected in ["HTTP 실패 1건", "늦게 출발한 요청 1건", "결제 실패 주문 1건", "구매 근거 미완료 1건",
                         "order-license-grant 소비 0건(기대 1)"]:
            self.assertIn(expected, text)


class MeasureTest(unittest.TestCase):
    def test_runs_load_then_journey_then_broker_and_excludes_warmup(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            calls = []

            def run(command, label):
                calls.append(label)
                if label == "measure-load":
                    (output / "load.json").write_text(json.dumps({"orders": [
                        {"status": 200, "httpMs": 900.0, "lateMs": 1.0, "warmup": True},
                        {"status": 200, "httpMs": 10.0, "lateMs": 1.0, "warmup": False},
                        {"status": 200, "httpMs": 30.0, "lateMs": 2.0, "warmup": False}]}))
                if label == "measure-journey":
                    (output / "journey.json").write_text(json.dumps(
                        {"purchase": {"completed": 2, "incomplete": 0, "failed": 0}}))
                if label == "measure-broker":
                    self.assertIn("--keys-from", command)
                    (output / "broker.json").write_text(json.dumps({"groups": broker(2)}))

            summary = messaging_measurement.measure("/repo", ["docker", "compose"], run,
                                                    lambda command: "mysql-id", output, 300, 5, 1, 2, 1)

        self.assertEqual(calls, ["measure-load", "measure-journey", "measure-broker"])
        self.assertEqual(summary["planned"], 2, "예열 주문은 집계에서 뺀다")
        self.assertEqual(summary["http_p95_ms"], 30.0)
        self.assertTrue(summary["validity"]["valid"])


if __name__ == "__main__":
    unittest.main()
