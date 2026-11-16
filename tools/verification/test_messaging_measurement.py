import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from support import messaging_measurement  # noqa: E402


class CommandTest(unittest.TestCase):
    def test_load_command_passes_the_offset_and_load_shape_to_the_driver(self):
        command = messaging_measurement.load_command(300, 5, 120, 20, "/tmp/load.json")
        self.assertEqual(command[:2], ["./gradlew", ":e2e:messagingLoadTest"])
        for flag in ["-Dstove.e2e.port-offset=300", "-Dstove.e2e.load-rate=5",
                     "-Dstove.e2e.load-duration-secs=120", "-Dstove.e2e.load-buyers=20",
                     "-Dstove.e2e.load-evidence=/tmp/load.json"]:
            self.assertIn(flag, command)


class MeasureTest(unittest.TestCase):
    def test_runs_load_then_journey_then_broker_with_the_window_from_the_journey(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            calls = []

            def run(command, label):
                calls.append(label)
                if label == "measure-load":
                    (output / "load.json").write_text(json.dumps(
                        {"planned": 2, "accepted": 2, "orders": [{"httpMs": 10.0}, {"httpMs": 30.0}]}))
                if label == "measure-journey":
                    (output / "journey.json").write_text(json.dumps(
                        {"window": {"since": "2026-11-16 10:00:00.000001", "until": "2026-11-16 10:01:00.000001"},
                         "purchase": {"completed": 2}}))
                if label == "measure-broker":
                    self.assertIn("2026-11-16 10:00:00.000001", command)
                    (output / "broker.json").write_text(json.dumps({"groups": {"license-paid-order": {"count": 2}}}))

            summary = messaging_measurement.measure("/repo", ["docker", "compose"], run,
                                                    lambda command: "mysql-id", output, 300, 5, 1, 2)

        self.assertEqual(calls, ["measure-load", "measure-journey", "measure-broker"])
        self.assertEqual(summary["http_p95_ms"], 30.0)
        self.assertEqual(summary["journey"]["completed"], 2)
        self.assertEqual(summary["broker"]["license-paid-order"]["count"], 2)


if __name__ == "__main__":
    unittest.main()
