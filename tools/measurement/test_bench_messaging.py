import unittest

import bench_messaging

DESCRIBE = """
GROUP           TOPIC             PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID         HOST        CLIENT-ID
bench-messaging bench.messages.v1 0          10              12              2    consumer-1-abc      /127.0.0.1  consumer-1
bench-messaging bench.messages.v1 1          5               5               0    consumer-2-def      /127.0.0.1  consumer-2
bench-messaging bench.messages.v1 2          -               7               -    -                   -           -
"""


class LagTest(unittest.TestCase):
    def test_parses_per_partition_lag_and_assigned_members(self):
        lag = bench_messaging.parse_lag(DESCRIBE)
        self.assertEqual(lag["partitions"], {0: 2, 1: 0, 2: None})
        self.assertEqual(lag["members"], 2)
        # 한 파티션이라도 지연을 모르면 합을 0으로 적지 않는다.
        self.assertIsNone(lag["total"])

    def test_total_when_every_partition_is_known(self):
        lag = bench_messaging.parse_lag(DESCRIBE.replace("-               7               -    -",
                                                         "7               7               0    consumer-3-x"))
        self.assertEqual(lag["total"], 2)
        self.assertEqual(lag["members"], 3)

    def test_no_group_yet_means_unknown_not_zero(self):
        lag = bench_messaging.parse_lag("Consumer group 'bench-messaging' does not exist.")
        self.assertEqual(lag, {"total": None, "partitions": {}, "members": 0})


class DeadlineTest(unittest.TestCase):
    def test_deadline_counts_from_the_generator_end_not_from_when_it_was_noticed(self):
        # 부하 종료 3초 뒤에 알아챘다면 남은 관찰 시간은 597초다.
        self.assertAlmostEqual(bench_messaging.observation_deadline(1000.0, 13_000_000, 10_000_000), 1597.0)
        # 시계가 어긋나 종료가 미래로 보여도 기한을 늘리지 않는다.
        self.assertAlmostEqual(bench_messaging.observation_deadline(1000.0, 9_000_000, 10_000_000), 1600.0)


class SettledTest(unittest.TestCase):
    def test_needs_every_acked_message_in_a_final_state_and_no_lag(self):
        self.assertTrue(bench_messaging.settled({"final": 10}, 10, 0))
        self.assertFalse(bench_messaging.settled({"final": 9}, 10, 0))
        self.assertFalse(bench_messaging.settled({"final": 10}, 10, None))
        self.assertFalse(bench_messaging.settled({"final": 10}, 10, 3))


class PrometheusTest(unittest.TestCase):
    def test_sums_series_across_tags_and_keeps_missing_as_none(self):
        text = "\n".join([
            "# HELP stove_kafka_consumer_failed_deliveries_total x",
            'stove_kafka_consumer_failed_deliveries_total{exception="A",group="g"} 3.0',
            'stove_kafka_consumer_failed_deliveries_total{exception="B",group="g"} 2.0',
            "jvm_gc_pause_seconds_sum 0.5",
        ])
        totals = bench_messaging.prometheus_totals(text, bench_messaging.METRICS)
        self.assertEqual(totals["stove_kafka_consumer_failed_deliveries_total"], 5.0)
        self.assertIsNone(totals["stove_kafka_dead_lettered_total"])
        delta = bench_messaging.metric_delta({"jvm_gc_pause_seconds_sum": 0.2}, totals)
        self.assertAlmostEqual(delta["jvm_gc_pause_seconds_sum"], 0.3)
        self.assertEqual(delta["stove_kafka_consumer_failed_deliveries_total"], 5.0)
        self.assertIsNone(delta["stove_kafka_dead_lettered_total"])


class ProcTest(unittest.TestCase):
    def test_cpu_seconds_skip_a_command_name_with_spaces(self):
        fields = ["S"] + ["0"] * 10 + ["250", "150"] + ["0"] * 30
        stat = "1234 (java (main) x) " + " ".join(fields)
        self.assertEqual(bench_messaging.proc_cpu_seconds(stat, 100), 4.0)

    def test_rss_from_status(self):
        self.assertEqual(bench_messaging.proc_rss_bytes("Name:\tjava\nVmRSS:\t  2048 kB\n"), 2048 * 1024)
        self.assertIsNone(bench_messaging.proc_rss_bytes("Name:\tjava\n"))


class ContainerArgsTest(unittest.TestCase):
    def test_kafka_matches_compose_and_binds_loopback_only(self):
        args = bench_messaging.kafka_run_args("esd-bench-x-kafka", 39092)
        self.assertIn("127.0.0.1:39092:9092", args)
        self.assertIn("KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:19092,EXTERNAL://127.0.0.1:39092", args)
        self.assertIn("--rm", args)
        self.assertEqual(args[-1], "apache/kafka:3.9.0")

    def test_mysql_has_the_bench_database_and_app_account(self):
        args = bench_messaging.mysql_run_args("esd-bench-x-mysql", 33306)
        self.assertIn("127.0.0.1:33306:3306", args)
        self.assertIn("MYSQL_DATABASE=stove_bench", args)
        self.assertIn("mysql:8.0", args)

    def test_topic_config_is_passed_only_when_given(self):
        plain = bench_messaging.topic_create_args("k", "t.DLT", 3)
        self.assertNotIn("--config", plain)
        limited = bench_messaging.topic_create_args("k", "t.DLT", 3, {"max.message.bytes": 512})
        self.assertEqual(limited[-2:], ["--config", "max.message.bytes=512"])


class WaitTest(unittest.TestCase):
    def test_retries_ordinary_failures_but_stops_at_once_when_the_process_died(self):
        calls = []

        def flaky():
            calls.append(1)
            if len(calls) < 3:
                raise ConnectionError("not yet")
            return True

        bench_messaging.wait_until(flaky, 5, "flaky", interval=0)
        self.assertEqual(len(calls), 3)

        class Dead:
            returncode = 1

            def poll(self):
                return 1

        with self.assertRaises(bench_messaging.Abort):
            bench_messaging.wait_until(lambda: bench_messaging.alive(Dead(), "app.log"), 60, "app", interval=0)


class DiskTest(unittest.TestCase):
    def test_reads_device_type_size_and_mount_from_df(self):
        line = "/dev/sda1      ext4  203056560 81234567 121822000  40% /"
        self.assertEqual(bench_messaging.df_line(line),
                         {"device": "/dev/sda1", "type": "ext4", "size_kib": 203056560, "mount": "/"})


def summary(**integrity):
    base = {"lost": 0, "duplicate_applied": 0, "order_violations": 0, "injected_but_completed": 0,
            "unexpected_final_failures": 0, "completed_and_failed": 0, "unacked_but_delivered": 0,
            "expected_final_failures": 3, "held_behind_stopped_key": 2}
    base.update(integrity)
    return {"integrity": base,
            "counts": {"ack_failed": 0, "quarantined_failed": 3, "dead_lettered": 0, "attempts": 100,
                       "completed_without_attempt_record": 0, "attempt_log_malformed_lines": 0},
            "retries": {"retry_succeeded": 5}}


class SmokeTest(unittest.TestCase):
    def test_clean_quarantine_run_passes(self):
        self.assertEqual(bench_messaging.smoke_problems(summary(), expect_dlt=False), [])

    def test_loss_duplicates_and_mismatched_final_failures_fail(self):
        problems = bench_messaging.smoke_problems(summary(lost=1, duplicate_applied=2, expected_final_failures=4),
                                                  expect_dlt=False)
        self.assertEqual(len(problems), 3)

    def test_dlt_run_counts_dead_letters_and_rejects_key_stops(self):
        problems = bench_messaging.smoke_problems(summary(), expect_dlt=True)
        self.assertIn("최종 실패 0건 ≠ 주입한 영구 실패 3건", problems)
        self.assertIn("DLT 계열인데 키 정지 보관이 있다", problems)

    def test_missing_attempt_records_fail(self):
        broken = summary()
        broken["counts"]["completed_without_attempt_record"] = 2
        self.assertEqual(len(bench_messaging.smoke_problems(broken, expect_dlt=False)), 1)

    def test_a_run_without_injected_failures_cannot_pass(self):
        problems = bench_messaging.smoke_problems(summary(expected_final_failures=0, held_behind_stopped_key=0),
                                                  expect_dlt=False)
        self.assertTrue(any("영구 실패를 하나도" in problem for problem in problems))


if __name__ == "__main__":
    unittest.main()
