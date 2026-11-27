import json
import os
import subprocess
import sys
import tempfile
import threading
import time
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


class HelpersTest(unittest.TestCase):
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

    def test_extra_environment_parses_assignments_and_refuses_malformed_ones(self):
        self.assertEqual(messaging_measurement.extra_environment(["A=1", "B=x=y"]), {"A": "1", "B": "x=y"})
        with self.assertRaises(SystemExit):
            messaging_measurement.extra_environment(["NOVALUE"])
        with self.assertRaises(SystemExit):
            messaging_measurement.extra_environment(["=value"])

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

    def test_rejected_refunds_invalidate_the_run_but_unfinished_ones_are_only_counted(self):
        measured = [{"status": 200, "refundStatus": 202, "refundState": "COMPLETED"},
                    {"status": 200, "refundStatus": 202, "refundState": "PENDING_REVIEW"},
                    {"status": 200, "refundStatus": 409, "refundState": "HTTP_409"},
                    {"status": 200}]
        self.assertEqual(messaging_measurement.refund_problems(measured), (3, 1, 2))
        reasons = messaging_measurement.validity(measured, {"completed": 0}, {}, {})["reasons"]
        self.assertIn("환불 요청 거절 1건(요청 3)", reasons)
        self.assertFalse(any("환불 미완료" in reason for reason in reasons), "모의 PG 스택에서는 환불이 끝날 수 없다")

    def test_load_command_passes_the_refund_interval(self):
        command = messaging_measurement.load_command(300, 2, 60, 10, "/tmp/load.json", 10, 4)
        self.assertIn("-Dstove.e2e.load-refund-every=4", command)



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


LAG_DESCRIBE = """
GROUP              TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
license-paid-order stove.order.v1  0          5               7               2    c-1          /h    c
license-paid-order stove.order.v1  1          -               3               -    c-2          /h    c

GROUP                 TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
settlement-paid-order stove.order.v1 0          7               7               0    c-3          /h    c
"""


class LagTest(unittest.TestCase):
    def test_sums_lag_per_group_and_counts_partitions_without_commits(self):
        groups = messaging_measurement.parse_group_lag(LAG_DESCRIBE)
        self.assertEqual(groups, {"license-paid-order": {"lag": 2, "unknown": 1},
                                  "settlement-paid-order": {"lag": 0, "unknown": 0}})

    def test_a_partition_with_nothing_to_consume_is_known_zero(self):
        text = LAG_DESCRIBE.replace("license-paid-order stove.order.v1  1          -               3               -",
                                    "license-paid-order stove.order.v1  1          -               0               -")
        self.assertEqual(messaging_measurement.parse_group_lag(text)["license-paid-order"], {"lag": 2, "unknown": 0})


class DrainTest(unittest.TestCase):
    S = 1_000_000
    G = {"g": {"lag": 0, "unknown": 0}}
    SAMPLES = [{"at_us": 10 * S, "total": 4, "unknown": 0, "groups": G}, {"at_us": 20 * S, "total": 0, "unknown": 0, "groups": G},
               {"at_us": 30 * S, "total": 2, "unknown": 0, "groups": G}, {"at_us": 35 * S, "total": 0, "unknown": 1, "groups": G},
               {"at_us": 38 * S, "total": 0, "unknown": 0, "groups": {}}, {"at_us": 40 * S, "total": 0, "unknown": 0, "groups": G}]

    def test_backlog_drain_needs_outstanding_and_lag_zero_after_the_last_finish(self):
        # 업무는 25초에 끝났다. 35초(모르는 파티션 있음)·38초(그룹 없음)는 지연 0을 안 것이 아니다 → 40초.
        self.assertEqual(messaging_measurement.backlog_drain_seconds(0, 25 * self.S, 0, self.SAMPLES), 40.0)
        self.assertIsNone(messaging_measurement.backlog_drain_seconds(0, 25 * self.S, 3, self.SAMPLES),
                          "미해소 주문이 있으면 해소되지 않았다")
        self.assertIsNone(messaging_measurement.backlog_drain_seconds(0, 45 * self.S, 0, self.SAMPLES), "지연 0을 관측하지 못했다")

    def test_load_end_is_the_drivers_last_request(self):
        self.assertEqual(messaging_measurement.load_end_time({"lastRequestEpochUs": 123}), 123)


class FaultValidityTest(unittest.TestCase):
    def test_a_failed_broker_restart_stays_an_injection_problem_in_a_fault_round(self):
        class Schedule:
            faults = [object()]

            def problems(self):
                return []

        class Restart:
            def problems(self):
                return ["브로커 재시작 실패"]

        result = messaging_measurement.with_faults({"valid": True, "reasons": ["구매 근거 미완료 2건"],
                                                    "business": ["구매 근거 미완료 2건"], "instrument": []}, Schedule(), Restart())
        self.assertFalse(result["valid"])
        self.assertEqual(result["reasons"], ["S7 장애 주입: 브로커 재시작 실패"])
        self.assertEqual(result["outcome"], ["구매 근거 미완료 2건"])

    def test_instrument_problems_stay_invalid_in_a_fault_round(self):
        class Schedule:
            faults = [object()]

            def problems(self):
                return []

        checked = messaging_measurement.validity([], {"completed": 0}, {group: {"count": 0} for group in
                                                                          messaging_measurement.PURCHASE_GROUPS}, {}, None,
                                                 {"clock_check": {"finish_before_start": 1}})
        result = messaging_measurement.with_faults(checked, Schedule())
        self.assertFalse(result["valid"])
        self.assertTrue(any("시각 불일치" in reason for reason in result["reasons"]))
        self.assertFalse(any("시각 불일치" in reason for reason in result["outcome"]))

    def test_inverted_driver_and_db_times_invalidate_the_round(self):
        checked = messaging_measurement.validity([], {"completed": 0}, {group: {"count": 0} for group in
                                                                          messaging_measurement.PURCHASE_GROUPS}, {}, None,
                                                 {"clock_check": {"finish_before_start": 3}})
        self.assertTrue(any("드라이버·DB 시각 불일치" in reason for reason in checked["reasons"]))

    def test_fault_round_validity_is_the_injection_and_business_breaks_become_outcome(self):
        class Schedule:
            faults = [object()]

            def problems(self):
                return []

        checked = {"valid": False, "reasons": ["구매 근거 미완료 3건"]}
        result = messaging_measurement.with_faults(checked, Schedule())
        self.assertTrue(result["valid"])
        self.assertEqual(result["outcome"], ["구매 근거 미완료 3건"])

    def test_a_fault_that_did_not_happen_invalidates_the_round(self):
        class Schedule:
            faults = [object()]

            def problems(self):
                return ["db-revoke: 권한 회수 실패"]

        result = messaging_measurement.with_faults({"valid": True, "reasons": []}, Schedule())
        self.assertFalse(result["valid"])
        self.assertEqual(result["reasons"], ["db-revoke: 권한 회수 실패"])

    def test_generator_shortfall_invalidates_a_normal_round(self):
        loaded = {"rate": 10.0, "measuredWindow": {"achievedRate": 9.5}}
        checked = messaging_measurement.validity([], {"completed": 0}, {group: {"count": 0} for group in
                                                                          messaging_measurement.PURCHASE_GROUPS}, {}, loaded)
        self.assertTrue(any("달성률" in reason for reason in checked["reasons"]))


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
                if label == "measure-timeseries":
                    self.assertIn("--load-ended-us", command)
                    (output / "timeseries.json").write_text(json.dumps({"unresolved": 0, "outstanding_drain_seconds": 1.5}))
                if label == "measure-broker":
                    self.assertIn("--keys-from", command)
                    (output / "broker.json").write_text(json.dumps(
                        {"groups": broker(2), "order": {"order-payment-result": {"keys": 2, "events": 2, "violations": 0}}}))

            selects = iter([100, 150, 450])

            disks = iter(["100\t/var/lib/mysql", "10\t/tmp/kraft-combined-logs",
                          "160\t/var/lib/mysql", "40\t/tmp/kraft-combined-logs"])

            def capture(command, **kwargs):
                if "kafka-consumer-groups.sh" in " ".join(command):
                    return LAG_DESCRIBE
                if command[:2] == ["docker", "stats"]:
                    return "mysql-id\t4.00%\t300MiB / 1GiB\nkafka-id\t2.00%\t500MiB / 2GiB"
                if command[:2] == ["docker", "exec"] and "du -sk" in command[-1]:
                    return next(disks)
                if command[:2] == ["docker", "exec"]:
                    value = next(selects)
                    return "Com_select\t%d\nQuestions\t%d" % (value, value * 2)
                return command[-1] + "-id"

            # 읽기마다 monotonic 두 번(전·후): 유휴 시작 0~0, 유휴 끝 10~10, 부하 끝 70~70
            clock = iter([0.0, 0.0, 10.0, 10.0, 70.0, 70.0])
            with mock.patch.object(messaging_measurement.time, "sleep"), \
                    mock.patch.object(messaging_measurement.time, "monotonic", side_effect=lambda: next(clock)):
                summary = messaging_measurement.measure("/repo", ["docker", "compose"], run, capture,
                                                        output, 300, 5, 1, 2, 1)

        self.assertEqual(calls, ["measure-load", "measure-journey", "measure-timeseries", "measure-broker"])
        self.assertEqual(summary["timeseries"]["outstanding_drain_seconds"], 1.5)
        self.assertIsNone(summary["faults"])
        self.assertEqual(summary["planned"], 2, "예열 주문은 집계에서 뺀다")
        self.assertEqual(summary["http_p95_ms"], 30.0)
        self.assertTrue(summary["validity"]["valid"])
        self.assertEqual(summary["db_load"]["idle_per_second"]["Com_select"], 5.0, "유휴 10초 동안 50건")
        self.assertEqual(summary["db_load"]["load_run_per_second"]["Com_select"], 5.0, "부하 구간 60초 동안 300건")
        self.assertEqual(summary["order"]["order-payment-result"]["violations"], 0)
        resources = summary["resources"]
        self.assertEqual(resources["disk"]["mysql"]["growth_kib"], 60)
        self.assertEqual(resources["disk"]["kafka"]["growth_kib"], 30)
        self.assertEqual(set(resources["load_run"]), {"mysql", "kafka"})
        self.assertEqual(resources["load_run"]["kafka"]["memory_mib_max"], 500.0)
        self.assertEqual(resources["stats_partial"], 0)
        self.assertEqual(resources["disk_errors"], {"before": {}, "after": {}})
        self.assertEqual(calls.index("measure-load"), 0)

    def test_idle_failure_still_stops_the_sampler(self):
        started = []

        class Recorder:
            def __init__(self, capture, containers):
                self.failures = 0
                self.partial = 0
                self.stopped = False
                started.append(self)

            def start(self):
                return self

            def stop(self):
                self.stopped = True
                return []

        def capture(command, **kwargs):
            if command[:2] == ["docker", "exec"] and "du -sk" in command[-1]:
                return "1\t/x"
            if command[:2] == ["docker", "exec"]:
                raise subprocess.CalledProcessError(1, command)
            return "id"

        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(messaging_measurement.resource_sampling, "Sampler", Recorder), \
                mock.patch.object(messaging_measurement.time, "sleep"):
            with self.assertRaises(subprocess.CalledProcessError):
                messaging_measurement.measure("/repo", ["docker", "compose"], lambda *a, **k: None, capture,
                                              Path(directory), 300, 5, 1, 2, 1)
        self.assertEqual(len(started), 1)
        self.assertTrue(started[0].stopped, "유휴 읽기가 실패해도 표본 스레드를 멈춘다(R3)")


class BrokerRestartTest(unittest.TestCase):
    def test_kills_then_starts_the_broker_service_and_records_when(self):
        commands = []

        def capture(command, **kwargs):
            commands.append(command)
            return ""

        restart = messaging_measurement.BrokerRestart(capture, ["docker", "compose", "-p", "x"], "kafka", 0.01, 0.01).start()
        restart.join()
        self.assertEqual(commands, [["docker", "compose", "-p", "x", "kill", "kafka"],
                                    ["docker", "compose", "-p", "x", "start", "kafka"]])
        summary = restart.summary()
        self.assertEqual(summary["service"], "kafka")
        self.assertIsNotNone(summary["killed_at_s"])
        self.assertGreaterEqual(summary["started_at_s"], summary["killed_at_s"])
        self.assertEqual(summary["errors"], [])

    def test_failed_command_is_recorded_not_raised(self):
        def capture(command, **kwargs):
            raise subprocess.CalledProcessError(1, command)

        restart = messaging_measurement.BrokerRestart(capture, ["docker"], "kafka", 0.0, 0.0).start()
        restart.join()
        self.assertIsNone(restart.killed_at)
        self.assertEqual(len(restart.errors), 2)

    def test_a_restart_that_did_not_happen_makes_the_run_invalid(self):
        def capture(command, **kwargs):
            if command[-2] == "kill":
                raise subprocess.CalledProcessError(1, command)
            return ""

        restart = messaging_measurement.BrokerRestart(capture, ["docker"], "kafka", 0.0, 0.0).start()
        restart.join()
        checked = messaging_measurement.with_restart({"valid": True, "reasons": []}, restart)
        self.assertFalse(checked["valid"])
        self.assertTrue(any("브로커 종료 실패" in reason for reason in checked["reasons"]))

    def test_diagnosis_slower_than_the_outage_does_not_delay_the_restart(self):
        # 중단 0.2초, 진단은 중단 한가운데(0.1초)에 시작해 0.8초 걸린다. 진단을 기다렸다 재시작하면 중단이 0.9초 이상이 된다(R5·R6).
        diagnosed = []

        def capture(command, **kwargs):
            return ""

        def slow_diagnosis():
            time.sleep(0.8)
            diagnosed.append(True)

        restart = messaging_measurement.BrokerRestart(capture, ["docker"], "kafka", 0.0, 0.2,
                                                      diagnose=slow_diagnosis).start()
        restart.join()
        self.assertLess(restart.actual_down(), 0.5, "재시작은 진단을 기다리지 않고 예정 시각에 한다")
        self.assertEqual(diagnosed, [True], "진단은 재시작 뒤에도 끝까지 돈다")
        self.assertEqual(restart.problems(), [])

    def test_diagnosis_that_never_finishes_is_recorded_and_the_run_goes_on(self):
        def capture(command, **kwargs):
            return ""

        stuck = threading.Event()
        with mock.patch.object(messaging_measurement, "DIAGNOSIS_JOIN_SECONDS", 0.1):
            restart = messaging_measurement.BrokerRestart(capture, ["docker"], "kafka", 0.0, 0.05,
                                                          diagnose=stuck.wait).start()
            restart.join()
        stuck.set()
        self.assertIsNotNone(restart.started_at)
        self.assertTrue(any("끝나지 않았다" in error for error in restart.errors))
        self.assertEqual(restart.problems(), [], "진단 실패는 장애 주입 실패가 아니다")

    def test_no_restart_keeps_validity_as_is(self):
        checked = {"valid": True, "reasons": []}
        self.assertIs(messaging_measurement.with_restart(checked, None), checked)

    def test_restart_round_kills_the_kafka_container(self):
        created = []

        class Recorder:
            def __init__(self, capture, docker, service, at, down, diagnose=None):
                self.service, self.at, self.down = service, at, down
                created.append(self)

            def start(self):
                return self

        # 부하 드라이버가 실패하면 수집 단계 전에 멈춘다 — 재시작이 어느 컨테이너를 겨누는지만 본다.
        def run(command, label):
            raise RuntimeError(label)

        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(messaging_measurement, "BrokerRestart", Recorder), \
                mock.patch.object(messaging_measurement.time, "sleep"):
            with self.assertRaises(RuntimeError):
                messaging_measurement.measure("/repo", ["docker", "compose"], run,
                                              lambda command, **kwargs: "1\t/x" if "du -sk" in command[-1]
                                              else ("Com_select\t1\nQuestions\t1" if command[:2] == ["docker", "exec"] else "id"),
                                              Path(directory), 300, 5, 1, 2, 1, restart_at=1.0, restart_down=2.0)
        self.assertEqual([(r.service, r.at, r.down) for r in created], [("kafka", 1.0, 2.0)])

if __name__ == "__main__":
    unittest.main()
