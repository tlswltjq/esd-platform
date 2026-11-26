import tempfile
import unittest
from pathlib import Path
from unittest import mock

from support import e2e_faults


class ParseTest(unittest.TestCase):
    def test_reads_kind_and_options(self):
        self.assertEqual(e2e_faults.parse("db-revoke:at=60,for=3,table=stove_license.product_license"),
                         ("db-revoke", {"at": "60", "for": "3", "table": "stove_license.product_license"}))
        self.assertEqual(e2e_faults.parse("malformed:at=5,topic=stove.order.v1"), ("malformed", {"at": "5", "topic": "stove.order.v1"}))

    def test_rejects_unknown_kinds_missing_keys_and_unsafe_names(self):
        for spec in ("pause:at=1", "kill:at=1,for=2", "db-revoke:at=1,for=2,table=stove_license.x;drop",
                     "kill:at=1,for=2,service=Lic ense", "malformed:at=-1,topic=t", "malformed:at=1,topic=a|b"):
            with self.assertRaises(ValueError, msg=spec):
                e2e_faults.parse(spec)


class ParsersTest(unittest.TestCase):
    def test_table_grants_replace_the_schema_grant(self):
        sql = e2e_faults.table_grant_sql("stove_license", ["product_license", "processed_event"])
        self.assertIn("GRANT ALL PRIVILEGES ON stove_license.product_license TO 'stove'@'%';", sql)
        self.assertTrue(sql.endswith("REVOKE ALL PRIVILEGES ON stove_license.* FROM 'stove'@'%';"))

    def test_topic_config_is_none_when_only_the_default_applies(self):
        explicit = "Dynamic configs for topic t are:\n  max.message.bytes=512 sensitive=false synonyms={DYNAMIC_TOPIC_CONFIG:max.message.bytes=512}"
        self.assertEqual(e2e_faults.topic_config_value(explicit, "max.message.bytes"), "512")
        self.assertIsNone(e2e_faults.topic_config_value("Dynamic configs for topic t are:\n", "max.message.bytes"))

    def test_log_dir_script_looks_in_both_kafka_data_paths_and_fails_when_none_exist(self):
        script = e2e_faults.log_dirs_script("stove.catalog.v1.DLT")
        self.assertIn("/tmp/kraft-combined-logs/stove.catalog.v1.DLT-*", script)
        self.assertIn("/tmp/kafka-logs/stove.catalog.v1.DLT-*", script)
        self.assertTrue(script.endswith('[ "$found" = 1 ]'))

    def test_partitions_batches_and_console_records(self):
        self.assertEqual(e2e_faults.partition_count("Topic: t\tTopicId: x\tPartitionCount: 3\tReplicationFactor: 1"), 3)
        dump = "baseOffset: 0 lastOffset: 0 count: 1 size: 4321 magic: 2\n| offset: 0 key: k\nbaseOffset: 1 size: 4400"
        self.assertEqual(e2e_faults.batch_sizes(dump), [4321, 4400])
        output = "CreateTime:1000|e13-dlt-x|{\"seq\":0}\nCreateTime:2000|other|v\nCreateTime:3000|e13-dlt-x|{\"seq\":1}"
        self.assertEqual(e2e_faults.console_records(output, "e13-dlt-x"), [(1000, '{"seq":0}'), (3000, '{"seq":1}')])

    def test_malformed_lines_have_keys_but_no_contract_headers(self):
        self.assertEqual(e2e_faults.malformed_lines("e13-malformed-r", 2),
                         'e13-malformed-r-0|{"e13":"malformed","seq":0}\ne13-malformed-r-1|{"e13":"malformed","seq":1}\n')


class FakeStack:
    """docker compose 명령을 받아 MySQL·Kafka 의 상태를 흉내 낸다."""

    def __init__(self):
        self.commands = []
        self.max_message_bytes = None
        self.dlt_records = []
        self.recovery_failures = 0.0
        self.dead_lettered = 0.0
        self.killed = False
        # Store 소비 그룹: 파티션 → [커밋 offset, 로그 끝]. 파티션 1에 두 건이 들어가고, 제한 중에는 커밋이 멈춘다.
        self.store = {0: [5, 5], 1: [None, 0], 2: [3, 3]}

    def capture(self, command, input=None, **_):
        self.commands.append((command, input))
        joined = " ".join(command)
        if "table_name = 'consumer_quarantine'" in joined:
            return "stove_license\nstove_settlement"
        if "information_schema.tables" in joined:
            return "product_license\nprocessed_event"
        if "information_schema.processlist" in joined:
            return "" if self.killed else "11\n12"
        if command[-1].startswith("KILL "):
            self.killed = True
            return ""
        if "--group store-catalog-publication" in joined:
            rows = ["GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID"]
            for partition, (committed, end) in sorted(self.store.items()):
                rows.append("store-catalog-publication stove.catalog.v1 %d %s %d %s c /h c" % (
                    partition, "-" if committed is None else committed, end, "-" if committed is None else end - committed))
            return "\n".join(rows)
        if "FROM stove_license.consumer_quarantine" in joined:
            return "license-paid-order|e13-malformed-r1-0\nsettlement-paid-order|e13-malformed-r1-0"
        if "kafka-consumer-groups.sh" in joined:
            return ("GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG\n"
                    "license-paid-order stove.order.v1 0 1 2 1\nsettlement-paid-order stove.order.v1 0 1 2 1\n"
                    "store-catalog-publication stove.catalog.v1 0 1 1 0")
        if "kafka-topics.sh" in joined and "--describe" in joined:
            return "Topic: stove.catalog.v1\tPartitionCount: 3\tReplicationFactor: 1"
        if "kafka-configs.sh" in joined and "--describe" in joined:
            if self.max_message_bytes is None:
                return "Dynamic configs for topic stove.catalog.v1.DLT are:"
            return "  max.message.bytes=%s sensitive=false" % self.max_message_bytes
        if "kafka-configs.sh" in joined and "--add-config" in joined:
            self.max_message_bytes = joined.split("max.message.bytes=")[1].split()[0]
            return ""
        if "kafka-configs.sh" in joined and "--delete-config" in joined:
            self.max_message_bytes = None
            # 설정이 풀리면 Store 재시도가 DLT 에 두 건을 순서대로 쓰고 원본 offset 을 넘긴다.
            self.dlt_records = ['{"e13":"dlt","seq":0}', '{"e13":"dlt","seq":1}']
            self.dead_lettered += 2
            self.store[1][0] = 2
            return ""
        if "kafka-console-producer.sh" in joined and "stove.catalog.v1" in joined:
            # 제한 중에는 DLT 쓰기가 거부돼 Store recoverer 가 실패하고 커밋이 멈춘다.
            self.recovery_failures += 3
            self.store[1][1] += 2
            return ""
        if "kafka-console-consumer.sh" in joined:
            return "\n".join("CreateTime:%d|%s|%s" % (5_000_000_000_000 + i, "e13-dlt-r1", value)
                             for i, value in enumerate(self.dlt_records))
        if command[-2:-1] == ["-c"] and "for d in" in command[-1]:
            return "/tmp/kraft-combined-logs/stove.catalog.v1.DLT-0"
        if "kafka-dump-log.sh" in joined:
            return "baseOffset: 0 lastOffset: 1 count: 2 size: 2048"
        return ""

    def fetch(self, url):
        return ("stove_kafka_consumer_recovery_failures_total{group=\"store\"} %s\n"
                "stove_kafka_dead_lettered_total{topic=\"t\"} %s\n" % (self.recovery_failures, self.dead_lettered))


def context(stack):
    return e2e_faults.Context(stack.capture, ["docker", "compose"], "root1234", stack.fetch, "http://store", run_tag="r1")


def setUpModule():
    # 실제 대기 시간을 없앤다(장애 길이는 시험마다 0초).
    e2e_faults.DbRevoke.EFFECT_WAIT_SECONDS = 0
    e2e_faults.DltLimit.SETTLE_SECONDS = 0


class DbRevokeTest(unittest.TestCase):
    def test_prepares_table_grants_reconnects_then_revokes_and_restores_insert(self):
        stack = FakeStack()
        fault = e2e_faults.DbRevoke(context(stack), {"at": "0", "for": "0", "table": "stove_license.product_license"})
        readings = iter([1.0, 4.0])
        fault.context.app_metrics = {"license": "http://license"}
        fault.context.fetch = lambda url: "stove_kafka_consumer_failed_deliveries_total %s\n" % next(readings)
        fault.prepare()
        fault.run()
        fault.cleanup()
        sql = [command[-1] for command, _ in stack.commands if command[-2] == "-e"]
        self.assertTrue(any(statement.startswith("GRANT ALL PRIVILEGES ON stove_license.product_license") for statement in sql))
        self.assertIn("KILL 11;", sql)
        self.assertIn("REVOKE INSERT ON stove_license.product_license FROM 'stove'@'%';", sql)
        self.assertIn("GRANT INSERT ON stove_license.product_license TO 'stove'@'%';", sql)
        # 정리에서 원래 스키마 단위 권한으로 되돌리고 표 단위 권한을 지운다(리뷰 R2).
        self.assertTrue(sql[-1].startswith("GRANT ALL PRIVILEGES ON stove_license.* TO 'stove'@'%';"))
        self.assertIn("REVOKE ALL PRIVILEGES ON stove_license.product_license FROM 'stove'@'%';", sql[-1])
        self.assertTrue(fault.facts["schema_grant_restored"])
        self.assertEqual(fault.problems(), [])

    def test_a_connection_that_survives_the_kill_is_reported(self):
        stack = FakeStack()
        original = stack.capture

        def stubborn(command, input=None, **kwargs):
            if command[-1].startswith("KILL "):
                return ""
            return original(command, input, **kwargs)

        fault = e2e_faults.DbRevoke(e2e_faults.Context(stubborn, ["docker", "compose"], "pw"),
                                    {"at": "0", "for": "0", "table": "stove_license.product_license"})
        fault.prepare()
        self.assertTrue(any("끊지 못했다" in problem for problem in fault.problems()))
        self.assertTrue(any("효과(소비 실패 수)를 읽지 못했다" in problem for problem in fault.problems()))

    def test_a_failed_grant_is_reported_and_retried_on_cleanup(self):
        stack = FakeStack()
        fault = e2e_faults.DbRevoke(context(stack), {"at": "0", "for": "0", "table": "stove_license.product_license"})
        original = stack.capture

        def failing(command, input=None, **kwargs):
            if command[-1].startswith("GRANT INSERT"):
                raise RuntimeError("connection lost")
            return original(command, input, **kwargs)

        fault.context.capture = failing
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        self.assertIn("권한 복원 실패", fault.problems())
        self.assertTrue(fault.revoked)
        # 종료 정리(cleanup)가 다시 되돌린다. 처음 복원 실패는 기록에 남는다.
        fault.context.capture = original
        fault.cleanup()
        self.assertFalse(fault.revoked)
        self.assertTrue(any(problem.startswith("grant:") for problem in fault.problems()))


class DbRevokeEffectTest(unittest.TestCase):
    def run_with_failures(self, before, after):
        stack = FakeStack()
        readings = iter([before, after])
        fault = e2e_faults.DbRevoke(context(stack), {"at": "0", "for": "0", "table": "stove_license.product_license"})
        fault.context.app_metrics = {"license": "http://license"}
        fault.context.fetch = lambda url: "stove_kafka_consumer_failed_deliveries_total{group=\"g\"} %s\n" % next(readings)
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        return fault

    def test_counts_consumer_failures_during_the_revoke(self):
        fault = self.run_with_failures(2.0, 9.0)
        self.assertEqual(fault.facts["failed_deliveries_during"], 7.0)
        self.assertEqual(fault.problems(), [])

    def test_a_revoke_without_any_consumer_failure_had_no_effect(self):
        fault = self.run_with_failures(2.0, 2.0)
        self.assertIn("권한 회수 동안 소비 실패가 없었다(장애가 효과를 내지 못했다)", fault.problems())


class ContainerKillTest(unittest.TestCase):
    def test_kills_then_starts_the_service(self):
        stack = FakeStack()
        fault = e2e_faults.ContainerKill(context(stack), {"at": "0", "for": "0", "service": "license"})
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        self.assertEqual([command for command, _ in stack.commands],
                         [["docker", "compose", "kill", "license"], ["docker", "compose", "start", "license"]])
        self.assertEqual(fault.problems(), [])


class MalformedTest(unittest.TestCase):
    def test_every_subscribed_group_must_quarantine_the_malformed_record(self):
        stack = FakeStack()
        fault = e2e_faults.MalformedEnvelopes(context(stack), {"at": "0", "topic": "stove.order.v1"})
        fault.QUARANTINE_WAIT_SECONDS = 1
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        self.assertEqual(fault.facts["subscribed_groups"], ["license-paid-order", "settlement-paid-order"])
        self.assertEqual(fault.facts["quarantined_groups"], ["license-paid-order", "settlement-paid-order"])
        published = [stdin for command, stdin in stack.commands if "kafka-console-producer.sh" in " ".join(command)]
        self.assertEqual(published, ['e13-malformed-r1-0|{"e13":"malformed","seq":0}\n'])
        self.assertEqual(fault.problems(), [])

    def test_a_group_that_did_not_quarantine_is_reported(self):
        stack = FakeStack()
        fault = e2e_faults.MalformedEnvelopes(context(stack), {"at": "0", "topic": "stove.order.v1"})
        fault.QUARANTINE_WAIT_SECONDS = 0
        fault.quarantined_pairs = lambda prefix: ["license-paid-order|e13-malformed-r1-0"]
        fault.run()
        self.assertTrue(any("격리되지 않은 (그룹|키) 1쌍" in problem for problem in fault.problems()))


class OffsetsTest(unittest.TestCase):
    def test_finds_the_single_partition_that_received_the_records(self):
        before = {0: (5, 5), 1: (None, 0), 2: (3, 3)}
        self.assertEqual(e2e_faults.locate_published(before, {0: (5, 5), 1: (None, 2), 2: (3, 3)}, 2), 1)
        # 다른 파티션도 함께 늘면(업무 이벤트) 어느 쪽인지 단정하지 않는다.
        self.assertIsNone(e2e_faults.locate_published(before, {0: (5, 7), 1: (None, 2), 2: (3, 3)}, 2))


class DltLimitTest(unittest.TestCase):
    def test_rejects_dlt_writes_while_limited_then_stores_both_records_once_in_order_after_restore(self):
        stack = FakeStack()
        fault = e2e_faults.DltLimit(context(stack), {"at": "0", "for": "0", "limit": "512"})
        fault.prepare()
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        self.assertEqual(fault.facts["original_max_message_bytes"], "기본값(명시 설정 없음)")
        self.assertEqual(fault.facts["recovery_failures_during"], 3.0)
        self.assertEqual(fault.facts["dead_lettered_during"], 0.0)
        self.assertEqual(fault.facts["dlt_records_after"], 2)
        self.assertTrue(fault.facts["limit_below_record"])
        # 원본 보존: 장애 중 파티션 1의 커밋이 첫 건(0)을 넘지 않았고, 복구 뒤 두 건을 지나 커밋됐다(리뷰 R3).
        self.assertEqual(fault.facts["target_partition"], 1)
        self.assertTrue(fault.facts["original_held_during"])
        self.assertEqual(fault.facts["target_committed_after"], 2)
        self.assertEqual(fault.facts["other_partitions_lag_during"], {0: 0, 2: 0})
        # 원래 명시 설정이 없었으니 덮어쓰지 않고 지워서 되돌린다(결정 235 안전장치).
        self.assertTrue(any("--delete-config" in command for command, _ in stack.commands))
        self.assertIsNone(stack.max_message_bytes)
        self.assertEqual(fault.problems(), [])

    def test_a_round_that_never_rejected_a_dlt_write_is_invalid(self):
        stack = FakeStack()
        fault = e2e_faults.DltLimit(context(stack), {"at": "0", "for": "0", "limit": "4096"})
        fault.prepare()
        # Store 가 DLT 쓰기 실패를 한 번도 보고하지 않는 경우.
        fault.context.fetch = lambda url: "stove_kafka_consumer_recovery_failures_total 0\nstove_kafka_dead_lettered_total 0\n"
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        problems = fault.problems()
        self.assertTrue(any("DLT 쓰기 실패를 일으키지 못했다" in problem for problem in problems))
        # 제한(4096)이 실제 배치(2048)보다 크면 그 회차는 결정 235 조건을 지키지 못한 것이다.
        self.assertTrue(any("작지 않다" in problem for problem in problems))

    def test_cleanup_restores_an_explicit_original_value(self):
        stack = FakeStack()
        stack.max_message_bytes = "1048588"
        fault = e2e_faults.DltLimit(context(stack), {"at": "0", "for": "0", "limit": "512"})
        fault.inspect()
        fault.configs("--alter", "--add-config", "max.message.bytes=512")
        fault.limited = True
        fault.cleanup()
        self.assertEqual(stack.max_message_bytes, "1048588")
        self.assertFalse(fault.limited)


class CancelTest(unittest.TestCase):
    def test_cleanup_wakes_a_running_kill_and_brings_the_container_back(self):
        stack = FakeStack()
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "load.json.started"
            marker.write_text(str(e2e_faults.now_us()))
            schedule = e2e_faults.FaultSchedule(context(stack), ["kill:at=0,for=3600,service=license"], marker, wait=5)
            schedule.start()
            for _ in range(100):
                if any(command[-2:] == ["kill", "license"] for command, _ in stack.commands):
                    break
                e2e_faults.time.sleep(0.01)
            schedule.cleanup()
        commands = [command for command, _ in stack.commands]
        self.assertEqual(commands[-1], ["docker", "compose", "start", "license"])
        self.assertEqual(schedule.problems(), [])

    def test_faults_not_yet_due_are_not_injected_after_cleanup(self):
        stack = FakeStack()
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "load.json.started"
            marker.write_text(str(e2e_faults.now_us()))
            schedule = e2e_faults.FaultSchedule(context(stack), ["kill:at=3600,for=1,service=license"], marker, wait=5)
            schedule.start()
            schedule.cleanup()
        self.assertEqual(stack.commands, [])
        self.assertIn("kill: 부하가 먼저 끝나 걸지 않았다", schedule.problems())


class ScheduleTest(unittest.TestCase):
    def test_waits_for_the_first_order_marker_and_reports_a_missing_one(self):
        stack = FakeStack()
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "load.json.started"
            schedule = e2e_faults.FaultSchedule(context(stack), ["kill:at=0,for=0,service=license"], marker, wait=0)
            schedule.start()
            schedule.join(5)
            self.assertIn("부하 시작 표시(load.json.started)가 생기지 않았다", schedule.problems())

            marker.write_text(str(e2e_faults.now_us()))
            schedule = e2e_faults.FaultSchedule(context(stack), ["kill:at=0,for=0,service=license"], marker, wait=5)
            schedule.start()
            schedule.join(5)
            self.assertEqual(schedule.problems(), [])
            self.assertIsNotNone(schedule.summary()["load_started_us"])


if __name__ == "__main__":
    unittest.main()
