import unittest

import journey_latency


class PurchaseQueryTest(unittest.TestCase):
    def test_joins_paid_orders_with_license_purchases_by_order_number(self):
        sql = journey_latency.purchase_sql("2026-11-16 10:00:00", "2026-11-16 10:10:00")
        self.assertIn("FROM stove_order.orders o JOIN stove_license.license_purchase lp", sql)
        self.assertIn("TIMESTAMPDIFF(MICROSECOND, o.created_at, lp.received_at)", sql)
        self.assertIn("TIMESTAMPDIFF(MICROSECOND, o.paid_at, lp.received_at)", sql)
        self.assertIn("o.created_at >= '2026-11-16 10:00:00'", sql)

    def test_rejects_unsafe_times(self):
        with self.assertRaises(ValueError):
            journey_latency.purchase_sql("x' OR '1", "2026-11-16 10:10:00")


class SummaryTest(unittest.TestCase):
    def test_summarizes_both_spans_and_counts_orders_without_a_purchase_basis(self):
        rows = [("ORD-1", "3000", "1000"), ("ORD-2", "5000", "2000"), ("ORD-3", "NULL", "NULL")]
        summary = journey_latency.summarize(rows)
        self.assertEqual(summary["completed"], 2)
        self.assertEqual(summary["incomplete"], 1)
        self.assertEqual(summary["created_to_basis"]["p50_ms"], 3.0)
        self.assertEqual(summary["paid_to_basis"]["max_ms"], 2.0)


if __name__ == "__main__":
    unittest.main()
