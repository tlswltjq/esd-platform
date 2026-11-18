import json
import os
import sys
import tempfile
import unittest
from unittest import mock
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

    def test_rabbitmq_points_messaging_apps_at_the_broker_with_the_shared_parallelism(self):
        order = messaging_measurement.broker_environment("order", "rabbitmq")
        self.assertEqual(order["STOVE_MESSAGING_BROKER"], "rabbitmq")
        self.assertEqual(order["SPRING_RABBITMQ_HOST"], "rabbitmq")
        self.assertEqual(order["STOVE_MESSAGING_RABBITMQ_QUEUESPERTOPIC"], str(messaging_measurement.PARALLELISM))
        self.assertNotIn("STOVE_MESSAGING_RABBITMQ_ALSOKAFKATOPICS", order)
        self.assertEqual(messaging_measurement.broker_environment("catalog", "rabbitmq")["STOVE_MESSAGING_RABBITMQ_ALSOKAFKATOPICS"],
                         "stove.catalog.v1")
        self.assertEqual(messaging_measurement.broker_environment("store", "rabbitmq"), {})
        self.assertEqual(messaging_measurement.broker_environment("auth", "rabbitmq")["STOVE_MESSAGING_RABBITMQ_OPTIONALTOPICS"],
                         "stove.auth.v1")
        self.assertNotIn("STOVE_MESSAGING_RABBITMQ_OPTIONALTOPICS", order)

    def test_only_the_rabbitmq_run_starts_a_rabbitmq_service(self):
        self.assertEqual(messaging_measurement.infra_services("kafka"), {})
        self.assertEqual(messaging_measurement.infra_services("direct"), {})
        rabbit = messaging_measurement.infra_services("rabbitmq")["rabbitmq"]
        self.assertEqual(rabbit["environment"]["RABBITMQ_DEFAULT_USER"], messaging_measurement.RABBIT_USER)
        self.assertIn("healthcheck", rabbit)

    def test_redis_streams_points_messaging_apps_at_their_own_redis_with_the_shared_parallelism(self):
        order = messaging_measurement.broker_environment("order", "redis-streams")
        self.assertEqual(order["STOVE_MESSAGING_BROKER"], "redis-streams")
        self.assertEqual(order["STOVE_MESSAGING_REDISSTREAMS_URI"], "redis://streams:6379")
        self.assertEqual(order["STOVE_MESSAGING_REDISSTREAMS_STREAMSPERTOPIC"], str(messaging_measurement.PARALLELISM))
        self.assertEqual(messaging_measurement.broker_environment("catalog", "redis-streams")["STOVE_MESSAGING_REDISSTREAMS_ALSOKAFKATOPICS"],
                         "stove.catalog.v1")
        streams = messaging_measurement.infra_services("redis-streams")["streams"]
        self.assertIn("--appendonly", streams["command"])

    def test_db_rate_divides_by_the_time_actually_elapsed_between_the_reads(self):
        before = ({"Com_select": 100, "Questions": 300}, 10.0)
        after = ({"Com_select": 650, "Questions": 1400}, 21.0)
        self.assertEqual(messaging_measurement.db_rate(before, after),
                         {"Com_select": 50.0, "Questions": 100.0, "seconds": 11.0}, "10초가 아니라 실제 11초로 나눈다")

    def test_db_counters_uses_the_given_password_and_the_middle_of_the_read_as_its_time(self):
        seen = {}

        def capture(command, **kwargs):
            seen["command"], seen["input"] = command, kwargs.get("input")
            return "Com_select\t42\nQuestions\t99"

        with mock.patch.dict(os.environ, {"MYSQL_ROOT_PASSWORD": "host-value"}), \
                mock.patch.object(messaging_measurement.time, "monotonic", side_effect=[5.0, 7.0]):
            counters, taken_at = messaging_measurement.db_counters(capture, "c1", "isolated-pw")
        self.assertEqual(counters, {"Com_select": 42, "Questions": 99})
        self.assertEqual(taken_at, 6.0)
        self.assertEqual(seen["command"][:4], ["docker", "exec", "-i", "c1"])
        self.assertIn("-pisolated-pw", seen["command"], "호스트의 MYSQL_ROOT_PASSWORD 가 아니라 격리 DB 값")
        self.assertIn("Com_select", seen["input"])

    def test_extra_environment_parses_assignments_and_refuses_the_broker_switch(self):
        self.assertEqual(messaging_measurement.extra_environment(["A=1", "B=x=y"]), {"A": "1", "B": "x=y"})
        with self.assertRaises(SystemExit):
            messaging_measurement.extra_environment(["NOVALUE"])
        with self.assertRaises(SystemExit):
            messaging_measurement.extra_environment(["STOVE_MESSAGING_BROKER=direct"])

    def test_a_same_key_order_violation_invalidates_the_run(self):
        result = messaging_measurement.validity([], {"completed": 0}, {}, {"order-payment-result": {"keys": 2, "events": 4, "violations": 1}})
        self.assertFalse(result["valid"])
        self.assertIn("order-payment-result 같은 키 순서 위반 1건", result["reasons"])

    def test_inbox_duplicates_sums_the_counter_per_consumer_group(self):
        text = "\n".join([
            "# TYPE stove_inbox_duplicates_total counter",
            'stove_inbox_duplicates_total{application="order",consumer_group="order-payment-result",event_type="A"} 2.0',
            'stove_inbox_duplicates_total{application="order",consumer_group="order-payment-result",event_type="B"} 1.0',
            'stove_inbox_duplicates_total{application="order",consumer_group="order-license-grant",event_type="A"} 0.0',
            'jvm_threads_live_threads 30.0'])
        self.assertEqual(messaging_measurement.inbox_duplicates(text),
                         {"order-payment-result": 3, "order-license-grant": 0})

    def test_redeliveries_keeps_unreadable_apps_instead_of_counting_them_as_zero(self):
        def fetch(url):
            if "bad" in url:
                raise OSError("refused")
            return 'stove_inbox_duplicates_total{consumer_group="g"} 4.0'

        self.assertEqual(messaging_measurement.redeliveries(fetch, ["http://ok", "http://bad"]),
                         {"by_group": {"g": 4}, "unreadable": ["http://bad"]})

    def test_unfinished_or_rejected_refunds_invalidate_the_run(self):
        measured = [{"status": 200, "refundStatus": 202, "refundState": "COMPLETED"},
                    {"status": 200, "refundStatus": 202, "refundState": "PENDING_REVIEW"},
                    {"status": 200, "refundStatus": 409, "refundState": "HTTP_409"},
                    {"status": 200}]
        self.assertEqual(messaging_measurement.refund_problems(measured), (3, 1, 2))
        reasons = messaging_measurement.validity(measured, {"completed": 0}, {}, {})["reasons"]
        self.assertIn("환불 요청 거절 1건(요청 3)", reasons)
        self.assertIn("환불 미완료 2건(요청 3)", reasons)

    def test_load_command_passes_the_refund_interval(self):
        command = messaging_measurement.load_command(300, 2, 60, 10, "/tmp/load.json", 10, 4)
        self.assertIn("-Dstove.e2e.load-refund-every=4", command)

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
                    (output / "broker.json").write_text(json.dumps(
                        {"groups": broker(2), "order": {"order-payment-result": {"keys": 2, "events": 2, "violations": 0}}}))

            selects = iter([100, 150, 450])

            def capture(command, **kwargs):
                if command[:2] == ["docker", "exec"]:
                    value = next(selects)
                    return "Com_select\t%d\nQuestions\t%d" % (value, value * 2)
                return "mysql-id"

            # 읽기마다 monotonic 두 번(전·후): 유휴 시작 0~0, 유휴 끝 10~10, 부하 끝 70~70
            clock = iter([0.0, 0.0, 10.0, 10.0, 70.0, 70.0])
            with mock.patch.object(messaging_measurement.time, "sleep"), \
                    mock.patch.object(messaging_measurement.time, "monotonic", side_effect=lambda: next(clock)):
                summary = messaging_measurement.measure("/repo", ["docker", "compose"], run, capture,
                                                        output, 300, 5, 1, 2, 1)

        self.assertEqual(calls, ["measure-load", "measure-journey", "measure-broker"])
        self.assertEqual(summary["planned"], 2, "예열 주문은 집계에서 뺀다")
        self.assertEqual(summary["http_p95_ms"], 30.0)
        self.assertTrue(summary["validity"]["valid"])
        self.assertEqual(summary["db_load"]["idle_per_second"]["Com_select"], 5.0, "유휴 10초 동안 50건")
        self.assertEqual(summary["db_load"]["load_run_per_second"]["Com_select"], 5.0, "부하 구간 60초 동안 300건")
        self.assertEqual(summary["order"]["order-payment-result"]["violations"], 0)


if __name__ == "__main__":
    unittest.main()
