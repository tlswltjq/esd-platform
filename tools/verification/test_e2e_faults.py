import subprocess
import tempfile
import threading
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
        self.target = e2e_faults.default_partition("e13-dlt-r1", 3)
        self.store = {0: [5, 5], 1: [None, 0], 2: [3, 3]}
        self.store[self.target] = [None, 0]
        # 원본 토픽 대상 파티션의 레코드 (offset, 키, 값). interloper 면 두 건 앞에 다른 키 한 건이 끼어든다.
        self.source = []
        self.interloper = False
        # 스키마 권한을 표 단위로 바꾼 표들. revoke_errors[표] 가 있으면 그 표의 REVOKE 가 그 오류로 실패한다.
        self.table_grants = set()
        self.revoke_errors = {}

    def capture(self, command, input=None, **_):
        self.commands.append((command, input))
        joined = " ".join(command)
        if "table_name = 'consumer_quarantine'" in joined:
            return "stove_license\nstove_settlement"
        if "information_schema.tables" in joined:
            return "product_license\nprocessed_event"
        sql = command[-1]
        if sql.startswith("SHOW GRANTS"):
            user = "`stove`@`" + "%`"
            lines = ["GRANT USAGE ON *.* TO " + user, "GRANT ALL PRIVILEGES ON `stove_license`.* TO " + user]
            return "\n".join(lines + ["GRANT ALL PRIVILEGES ON `stove_license`.`%s` TO %s" % (table, user)
                                      for table in sorted(self.table_grants)])
        if sql.startswith("GRANT ALL PRIVILEGES ON stove_license.") and "REVOKE ALL PRIVILEGES ON stove_license.*" in sql:
            self.table_grants = {part.split("ON stove_license.")[1].split()[0]
                                 for part in sql.split(";") if part.strip().startswith("GRANT")}
            return ""
        if sql.startswith("REVOKE ALL PRIVILEGES ON stove_license.") and ".*" not in sql:
            table = sql.split("ON stove_license.")[1].split()[0]
            if table in self.revoke_errors:
                raise subprocess.CalledProcessError(1, command, output=self.revoke_errors[table])
            self.table_grants.discard(table)
            return ""
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
            self.store[self.target][0] = 2
            return ""
        if "kafka-console-producer.sh" in joined and "stove.catalog.v1" in joined:
            # 제한 중에는 DLT 쓰기가 거부돼 Store recoverer 가 실패하고 커밋이 멈춘다.
            self.recovery_failures += 3
            if self.interloper:
                self.source.append((len(self.source), "other", "{}"))
            for value in ('{"e13":"dlt","seq":0}', '{"e13":"dlt","seq":1}'):
                self.source.append((len(self.source), "e13-dlt-r1", value))
            self.store[self.target][1] = len(self.source)
            return ""
        if "kafka-console-consumer.sh" in joined and "--partition" in joined:
            start = int(command[command.index("--offset") + 1])
            return "\n".join("Offset:%d|%s|%s" % record for record in self.source if record[0] >= start)
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
        # 정리에서 원래 스키마 단위 권한으로 되돌리고 표 단위 권한을 하나씩 지운다(리뷰 R2).
        # 지울 표는 SHOW GRANTS 로 읽고, 지운 뒤 다시 읽어 하나도 남지 않았는지 확인한다(재리뷰 2 R1).
        self.assertEqual(sql[-5:], ["GRANT ALL PRIVILEGES ON stove_license.* TO 'stove'@'%';",
                                    "SHOW GRANTS FOR 'stove'@'%';",
                                    "REVOKE ALL PRIVILEGES ON stove_license.processed_event FROM 'stove'@'%';",
                                    "REVOKE ALL PRIVILEGES ON stove_license.product_license FROM 'stove'@'%';",
                                    "SHOW GRANTS FOR 'stove'@'%';"])
        self.assertEqual(stack.table_grants, set())
        self.assertTrue(fault.facts["schema_grant_restored"])
        self.assertTrue(fault.restored())
        self.assertEqual(fault.problems(), [])

    def prepared(self, revoke_errors):
        stack = FakeStack()
        stack.revoke_errors = revoke_errors
        fault = e2e_faults.DbRevoke(context(stack), {"at": "0", "for": "0", "table": "stove_license.product_license"})
        fault.prepare()
        return stack, fault

    def test_a_connection_error_on_a_table_revoke_is_a_failed_restore_not_a_missing_grant(self):
        stack, fault = self.prepared({"processed_event": "ERROR 2013 (HY000): Lost connection to MySQL server during query"})
        fault.restore_schema_grant()
        # 한 표가 실패해도 나머지는 지운다. 연결 오류를 "권한 없음"으로 덮지 않는다.
        self.assertEqual(stack.table_grants, {"processed_event"})
        self.assertEqual(fault.facts["table_grants_left"], ["processed_event"])
        self.assertFalse(fault.restored())
        problems = fault.problems()
        self.assertIn("스키마 권한 복원 실패", problems)
        self.assertTrue(any("processed_event" in problem and "Lost connection" in problem for problem in problems))

    def test_mysql_no_such_grant_error_is_an_already_removed_grant(self):
        stack, fault = self.prepared({"processed_event": "ERROR 1147 (42000): There is no such grant defined for user "
                                                         "'stove' on host '%' on table 'processed_event'"})
        original = stack.capture

        def removed_meanwhile(command, input=None, **kwargs):
            # SHOW GRANTS 로 읽은 뒤 REVOKE 전에 그 표 권한이 이미 없어진 경우: MySQL 은 1147 로 거절한다.
            if command[-1].startswith("REVOKE ALL PRIVILEGES ON stove_license.processed_event"):
                stack.table_grants.discard("processed_event")
            return original(command, input, **kwargs)

        fault.context.capture = removed_meanwhile
        fault.restore_schema_grant()
        self.assertEqual(stack.table_grants, set())
        self.assertTrue(fault.restored())
        self.assertEqual(fault.errors, [])
        self.assertNotIn("스키마 권한 복원 실패", fault.problems())

    def test_unreadable_grants_fail_the_restore(self):
        stack, fault = self.prepared({})
        original = stack.capture

        def no_show(command, input=None, **kwargs):
            if command[-1].startswith("SHOW GRANTS"):
                raise subprocess.TimeoutExpired(command, 120)
            return original(command, input, **kwargs)

        fault.context.capture = no_show
        fault.restore_schema_grant()
        self.assertFalse(fault.restored())
        self.assertIn("스키마 권한 복원 실패", fault.problems())

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


class PartitionerTest(unittest.TestCase):
    def test_murmur2_matches_kafkas_published_vectors(self):
        # kafka-clients UtilsTest.testMurmur2 의 값.
        for text, expected in (("21", -973932308), ("foobar", -790332482), ("a-little-bit-long-string", -985981536),
                               ("a-little-bit-longer-string", -1486304829), ("abc", 479470107),
                               ("lkjh234lh9fiuh90y23oiuhsafujhadof229phr9h19h89h8", -58897971)):
            self.assertEqual(e2e_faults.murmur2(text.encode()), expected, text)

    def test_default_partition_is_positive_hash_modulo_partitions(self):
        self.assertEqual(e2e_faults.default_partition("a-little-bit-longer-string", 3), (-1486304829 & 0x7FFFFFFF) % 3)


class GroupOffsetsTest(unittest.TestCase):
    def test_reads_committed_and_end_offsets(self):
        text = ("GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n"
                "g stove.catalog.v1 0 5 5 0 c /h c\ng stove.catalog.v1 1 - 2 - c /h c\ng other 0 1 1 0 c /h c")
        self.assertEqual(e2e_faults.group_offsets(text, "stove.catalog.v1"), {0: (5, 5), 1: (None, 2)})


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
        self.assertEqual(fault.facts["target_partition"], stack.target)
        self.assertTrue(fault.facts["original_held_during"])
        self.assertEqual(fault.facts["published_offsets"], [0, 1])
        self.assertEqual(fault.facts["target_committed_after"], 2)
        self.assertTrue(fault.restored())
        self.assertEqual(set(fault.facts["other_partitions_lag_during"].values()), {0})
        # 원래 명시 설정이 없었으니 덮어쓰지 않고 지워서 되돌린다(결정 235 안전장치).
        self.assertTrue(any("--delete-config" in command for command, _ in stack.commands))
        self.assertIsNone(stack.max_message_bytes)
        self.assertEqual(fault.problems(), [])

    def test_offsets_come_from_the_published_records_not_the_log_end_before_publishing(self):
        stack = FakeStack()
        stack.interloper = True
        fault = e2e_faults.DltLimit(context(stack), {"at": "0", "for": "0", "limit": "512"})
        fault.RESTORE_WAIT_SECONDS = 0
        fault.prepare()
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        # 발행 전 로그 끝은 0 이었지만 다른 키 한 건이 0 에 끼어 두 건은 1·2 다.
        # 복구 뒤 커밋 2 는 두 번째 건(2)을 지나지 않았다 — 통과시키지 않는다(재리뷰 2 R2).
        self.assertEqual(fault.facts["published_offsets"], [1, 2])
        self.assertEqual(fault.facts["target_first_offset"], 1)
        self.assertEqual(fault.facts["target_last_offset"], 2)
        self.assertIsNone(fault.facts.get("target_committed_after"))
        self.assertIn("복구 뒤 원본 offset 이 두 건을 지나 커밋되지 않았다", fault.problems())

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

    def test_a_missing_target_partition_sample_during_the_fault_is_not_a_held_offset(self):
        stack = FakeStack()
        fault = e2e_faults.DltLimit(context(stack), {"at": "0", "for": "0", "limit": "512"})
        fault.prepare()
        original = fault.store_offsets
        calls = []

        def without_target_during():
            offsets = original()
            calls.append(1)
            if len(calls) == 2:  # 장애 중 표본에서 대상 파티션 행이 빠진 경우
                offsets.pop(stack.target, None)
            return offsets

        fault.store_offsets = without_target_during
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        # 행이 없는 것을 "커밋 없음"으로 읽어 원본 보존을 통과시키지 않는다(재리뷰 3 R2).
        self.assertFalse(fault.facts["target_sampled_during"])
        self.assertNotIn("original_held_during", fault.facts)
        self.assertTrue(any("커밋 표본이 없다" in problem for problem in fault.problems()))

    def test_the_published_records_are_read_after_the_limit_is_restored(self):
        stack = FakeStack()
        fault = e2e_faults.DltLimit(context(stack), {"at": "0", "for": "0", "limit": "512"})
        fault.prepare()
        with mock.patch.object(e2e_faults.time, "sleep"):
            fault.run()
        joined = [" ".join(command) for command, _ in stack.commands]
        restore = next(index for index, text in enumerate(joined) if "--delete-config" in text)
        read = next(index for index, text in enumerate(joined) if "kafka-console-consumer.sh" in text and "--partition" in text)
        # 원본 로그 조회(최대 6초)가 장애 창을 늘리지 않게 복원 뒤에 읽는다(재리뷰 3 R3).
        self.assertGreater(read, restore)
        self.assertEqual(fault.problems(), [])

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


class CancelledStepTest(unittest.TestCase):
    def test_a_cancelled_fault_does_not_inject_but_still_restores(self):
        stack = FakeStack()
        fault = e2e_faults.ContainerKill(context(stack), {"at": "0", "for": "0", "service": "license"})
        fault.cancelled.set()
        fault.run()
        self.assertEqual(stack.commands, [])
        self.assertIn("kill: 취소돼 하지 않았다", fault.problems())


class RestoreLockTest(unittest.TestCase):
    def test_a_late_fault_thread_and_cleanup_do_not_restore_at_the_same_time(self):
        stack = FakeStack()
        fault = e2e_faults.DltLimit(context(stack), {"at": "0", "for": "0", "limit": "512"})
        fault.inspect()
        fault.limited = True
        original = stack.capture
        entered = threading.Event()
        active = []

        def slow(command, input=None, **kwargs):
            if "--delete-config" in command:
                active.append(1)
                entered.set()
                self.assertEqual(len(active), 1, "복원 두 개가 함께 돌았다")
                e2e_faults.time.sleep(0.2)
                active.pop()
            return original(command, input, **kwargs)

        fault.context.capture = slow
        late = threading.Thread(target=fault.restore)
        late.start()
        entered.wait(5)
        # 장애 스레드가 아직 복원 중이면 정리는 그 복원이 끝나길 기다리고, 이미 되돌려졌으면 다시 하지 않는다.
        fault.cleanup()
        late.join()
        self.assertEqual(sum(1 for command, _ in stack.commands if "--delete-config" in command), 1)
        self.assertTrue(fault.restored())


class UnconfirmedRestoreTest(unittest.TestCase):
    def test_a_fault_thread_alive_after_the_cleanup_limit_leaves_the_restore_unconfirmed(self):
        stack = FakeStack()
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "load.json.started"
            marker.write_text(str(e2e_faults.now_us()))
            schedule = e2e_faults.FaultSchedule(context(stack), ["kill:at=0,for=0,service=license"], marker, wait=5)
            release = threading.Event()
            fault = schedule.faults[0]
            fault.run = lambda: release.wait(5)
            schedule.start()
            for _ in range(100):
                if schedule._threads:
                    break
                e2e_faults.time.sleep(0.01)
            with mock.patch.object(e2e_faults, "CLEANUP_JOIN_SECONDS", 0.05):
                schedule.cleanup()
            release.set()
        summary = schedule.summary()
        self.assertEqual(summary["restore_unconfirmed"], ["kill"])
        self.assertIn("kill: 장애 스레드가 정리 시한 안에 끝나지 않았다", schedule.problems())

    def test_a_held_restore_lock_does_not_block_the_other_faults_restores(self):
        stack = FakeStack()
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "load.json.started"
            schedule = e2e_faults.FaultSchedule(context(stack), ["kill:at=0,for=0,service=license",
                                                                 "kill:at=0,for=0,service=settlement"], marker, wait=5)
            first, second = schedule.faults
            first.down = second.down = True
            held, release = threading.Event(), threading.Event()

            def hold():
                with first.restoring:
                    held.set()
                    release.wait(5)

            holder = threading.Thread(target=hold)
            holder.start()
            held.wait(5)
            with mock.patch.object(e2e_faults, "CLEANUP_JOIN_SECONDS", 0.05):
                schedule.cleanup()
            release.set()
            holder.join()
        # 첫 장애의 잠금을 다른 스레드가 쥐고 있어도 둘째 장애는 되돌리고, 첫 장애는 미확인으로 남긴다(재리뷰 3 R1).
        self.assertIn(["docker", "compose", "start", "settlement"], [command for command, _ in stack.commands])
        self.assertEqual(schedule.restore_unconfirmed(), ["kill"])
        self.assertIn("kill: 복원 잠금을 정리 시한 안에 얻지 못했다", schedule.problems())

    def test_a_fault_left_unrestored_is_reported_as_unconfirmed(self):
        stack = FakeStack()
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "load.json.started"
            schedule = e2e_faults.FaultSchedule(context(stack), ["kill:at=0,for=0,service=license"], marker, wait=5)
            schedule.faults[0].down = True
            original = stack.capture

            def broken(command, input=None, **kwargs):
                if command[-2:] == ["start", "license"]:
                    raise RuntimeError("daemon unavailable")
                return original(command, input, **kwargs)

            schedule.context.capture = broken
            schedule.cleanup()
        self.assertEqual(schedule.summary()["restore_unconfirmed"], ["kill"])


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
