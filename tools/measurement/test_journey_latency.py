import unittest
from pathlib import Path

import journey_latency


class PurchaseQueryTest(unittest.TestCase):
    def test_reads_the_current_order_table_not_the_legacy_one(self):
        # 실측 3회에서 옛 표 orders를 읽어 0건이 나왔다. 주문 엔티티는 purchase_order다(V10).
        sql = journey_latency.purchase_sql_for_orders(["ORD-1"])
        self.assertIn("stove_order.purchase_order", sql)
        self.assertNotIn("stove_order.orders ", sql)
        self.assertNotIn("license_purchase", sql)

    def test_the_tables_it_reads_are_the_ones_the_entities_write(self):
        # 표 이름이 바뀌면 수집기가 0건을 조용히 내지 않고 여기서 멈추게 한다.
        root = Path(__file__).resolve().parents[2]
        order = (root / "apps/order/src/main/java/com/stove/order/core/domain/Order.java").read_text()
        license = (root / "apps/license/src/main/java/com/stove/license/core/domain/License.java").read_text()
        self.assertIn('@Table(name = "purchase_order")', order)
        self.assertIn('@Table(name = "product_license")', license)

    def test_joins_paid_orders_with_license_purchases_by_order_number(self):
        sql = journey_latency.purchase_sql("2026-11-16 10:00:00", "2026-11-16 10:10:00")
        self.assertIn("FROM stove_order.purchase_order o JOIN stove_license.product_license lp", sql)
        self.assertIn("TIMESTAMPDIFF(MICROSECOND, o.created_at, lp.received_at)", sql)
        self.assertIn("TIMESTAMPDIFF(MICROSECOND, o.result_occurred_at, lp.received_at)", sql)
        self.assertIn("o.created_at >= '2026-11-16 10:00:00'", sql)

    def test_rejects_unsafe_times(self):
        with self.assertRaises(ValueError):
            journey_latency.purchase_sql("x' OR '1", "2026-11-16 10:10:00")


class OrderListTest(unittest.TestCase):
    def test_reads_accepted_order_numbers_from_the_load_evidence(self):
        evidence = {"orders": [{"status": 200, "orderNo": "ORD-1"}, {"status": 500, "error": "x"},
                               {"status": 200, "orderNo": "W-1", "warmup": True},
                               {"status": 200, "orderNo": "ORD-2"}]}
        self.assertEqual(journey_latency.order_numbers(evidence), ["ORD-1", "ORD-2"], "예열 주문은 뺀다(R7)")

    def test_filters_by_order_numbers_instead_of_a_time_window(self):
        sql = journey_latency.purchase_sql_for_orders(["ORD-1", "ORD-2"])
        self.assertIn("o.order_no IN ('ORD-1','ORD-2')", sql)
        self.assertNotIn("o.created_at >=", sql)

    def test_rejects_unsafe_order_numbers(self):
        with self.assertRaises(ValueError):
            journey_latency.purchase_sql_for_orders(["ORD-1' OR '1"])

    def test_window_sql_spans_the_orders_from_creation_to_the_last_purchase_basis(self):
        sql = journey_latency.window_sql(["ORD-1"])
        self.assertIn("MIN(o.created_at)", sql)
        self.assertIn("MAX(lp.received_at)", sql)


class DiagnosisTest(unittest.TestCase):
    def test_counts_orders_and_purchases_for_the_evidence_numbers(self):
        sql = journey_latency.diagnosis_sql(["ORD-1"])
        self.assertIn("FROM stove_order.purchase_order o WHERE o.order_no IN ('ORD-1')", sql)
        self.assertIn("FROM stove_license.product_license lp WHERE lp.order_no IN ('ORD-1')", sql)


class SummaryTest(unittest.TestCase):
    def test_summarizes_both_spans_and_counts_orders_without_a_purchase_basis(self):
        rows = [("ORD-1", "3000", "1000", "PAID"), ("ORD-2", "5000", "2000", "PAID"), ("ORD-3", "NULL", "NULL", "PAID"),
                ("ORD-4", "NULL", "NULL", "FAILED")]
        summary = journey_latency.summarize(rows)
        self.assertEqual(summary["completed"], 2)
        self.assertEqual(summary["incomplete"], 1)
        self.assertEqual(summary["failed"], 1, "결제 실패는 미완료와 따로 센다(R5)")
        self.assertEqual(summary["created_to_basis"]["p50_ms"], 3.0)
        self.assertEqual(summary["paid_to_basis"]["max_ms"], 2.0)


if __name__ == "__main__":
    unittest.main()
