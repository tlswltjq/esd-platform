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


if __name__ == "__main__":
    unittest.main()
