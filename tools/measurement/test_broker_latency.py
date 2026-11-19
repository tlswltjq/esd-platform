import unittest

import broker_latency


class PercentileTest(unittest.TestCase):
    def test_nearest_rank(self):
        values = list(range(1, 101))
        self.assertEqual(broker_latency.percentile(values, 50), 50)
        self.assertEqual(broker_latency.percentile(values, 95), 95)
        self.assertEqual(broker_latency.percentile(values, 99), 99)
        self.assertEqual(broker_latency.percentile([7], 99), 7)

    def test_empty_has_no_percentile(self):
        self.assertIsNone(broker_latency.percentile([], 50))


class QueryTest(unittest.TestCase):
    def test_joins_every_publisher_outbox_with_every_consumer_inbox_by_event_id(self):
        sql = broker_latency.latency_sql(["order", "payment"], ["license", "order"],
                                         "2026-11-16 10:00:00", "2026-11-16 10:10:00")
        self.assertEqual(sql.count("UNION ALL"), 3)
        self.assertIn("FROM stove_order.outbox_event o JOIN stove_license.processed_event p ON p.event_id = o.event_id", sql)
        self.assertIn("FROM stove_payment.outbox_event o JOIN stove_order.processed_event p ON p.event_id = o.event_id", sql)
        self.assertIn("o.published_at >= '2026-11-16 10:00:00'", sql)
        self.assertIn("o.published_at < '2026-11-16 10:10:00'", sql)

    def test_rejects_unsafe_identifiers_and_times(self):
        with self.assertRaises(ValueError):
            broker_latency.latency_sql(["order; DROP"], ["license"], "2026-11-16 10:00:00", "2026-11-16 10:10:00")
        with self.assertRaises(ValueError):
            broker_latency.latency_sql(["order"], ["license"], "2026-11-16' OR '1", "2026-11-16 10:10:00")


class KeyScopeTest(unittest.TestCase):
    def test_limits_events_to_the_run_order_numbers(self):
        # 시각 창은 마지막 구매의 후속 이벤트를 자르고 다른 업무 이벤트를 섞는다(PR #206 R3).
        sql = broker_latency.latency_sql_for_keys(["order"], ["license"], ["ORD-1", "ORD-2"])
        self.assertIn("o.partition_key IN ('ORD-1','ORD-2')", sql)
        self.assertNotIn("o.published_at >=", sql)

    def test_rejects_unsafe_keys_and_empty_scope(self):
        with self.assertRaises(ValueError):
            broker_latency.latency_sql_for_keys(["order"], ["license"], ["ORD-1' OR '1"])
        with self.assertRaises(ValueError):
            broker_latency.latency_sql_for_keys(["order"], ["license"], [])

    def test_takes_only_accepted_measured_orders_as_keys(self):
        evidence = {"orders": [{"status": 200, "orderNo": "W-1", "warmup": True},
                               {"status": 200, "orderNo": "ORD-1", "warmup": False},
                               {"status": 500, "error": "x"}]}
        self.assertEqual(broker_latency.keys_from(evidence), ["ORD-1"])


class PasswordTest(unittest.TestCase):
    def test_the_password_never_appears_on_the_command_line(self):
        # 명령행 인수는 ps와 CalledProcessError 로그에 그대로 보인다(PR #206 R6).
        command = broker_latency.mysql_command("mysql-id")
        self.assertIn("MYSQL_PWD", command)
        self.assertFalse(any(part.startswith("MYSQL_PWD=") for part in command))

    def test_sql_goes_through_stdin_not_the_argument_list(self):
        # 실측 7회차: 키 120개 × 스키마 쌍 56개 SQL이 인수 길이 한도(E2BIG)를 넘었다.
        self.assertEqual(broker_latency.mysql_command("mysql-id")[-1], "-B", "mysql 뒤에 SQL 인수가 없다")


class SummaryTest(unittest.TestCase):
    def test_summarizes_per_consumer_group_in_milliseconds(self):
        rows = [("license-paid-order", "OrderPaid", str(us)) for us in (1000, 2000, 3000, 4000)]
        rows.append(("settlement-paid-order", "OrderPaid", "500"))
        summary = broker_latency.summarize(rows)
        self.assertEqual(summary["license-paid-order"]["count"], 4)
        self.assertEqual(summary["license-paid-order"]["p50_ms"], 2.0)
        self.assertEqual(summary["license-paid-order"]["max_ms"], 4.0)
        self.assertEqual(summary["settlement-paid-order"]["p99_ms"], 0.5)

    def test_counts_negative_spans_separately_as_a_measurement_fault(self):
        summary = broker_latency.summarize([("g", "E", "-10"), ("g", "E", "20")])
        self.assertEqual(summary["g"]["negative"], 1)
        self.assertEqual(summary["g"]["count"], 1)


class OrderTest(unittest.TestCase):
    def test_order_query_returns_outbox_and_inbox_ids_for_the_run_keys(self):
        sql = broker_latency.order_sql_for_keys(["payment"], ["order"], ["ORD-1"])
        self.assertIn("o.topic, o.partition_key, o.id, p.id", sql)
        self.assertIn("stove_payment.outbox_event o JOIN stove_order.processed_event p ON p.event_id = o.event_id", sql)
        self.assertIn("IN ('ORD-1')", sql)
        with self.assertRaises(ValueError):
            broker_latency.order_sql_for_keys(["payment"], ["order"], ["ORD-1'; --"])

    def test_counts_a_violation_when_a_later_event_of_the_key_was_processed_first(self):
        rows = [("g", "payment", "t", "K1", "10", "100"), ("g", "payment", "t", "K1", "11", "101"),  # 순서대로
                ("g", "payment", "t", "K2", "20", "203"), ("g", "payment", "t", "K2", "21", "202"),  # 뒤 이벤트가 먼저 처리
                ("h", "order", "t", "K1", "5", "7")]
        self.assertEqual(broker_latency.order_violations(rows), {
            "g": {"keys": 2, "events": 4, "pairs": 2, "violations": 1},
            "h": {"keys": 1, "events": 1, "pairs": 0, "violations": 0}})

    def test_does_not_compare_events_from_different_publishers(self):
        # 다른 토픽(발행 모듈)끼리는 순서를 보장하지 않는다.
        rows = [("g", "payment", "t", "K1", "10", "200"), ("g", "license", "t", "K1", "3", "100")]
        self.assertEqual(broker_latency.order_violations(rows)["g"]["violations"], 0)

    def test_does_not_compare_events_from_different_topics_of_the_same_publisher(self):
        # catalog 는 catalog·release 두 토픽을 발행한다(PR #216 R2).
        rows = [("g", "catalog", "stove.catalog.v1", "K1", "10", "200"),
                ("g", "catalog", "stove.release.v1", "K1", "11", "100")]
        self.assertEqual(broker_latency.order_violations(rows)["g"]["violations"], 0)


if __name__ == "__main__":
    unittest.main()
