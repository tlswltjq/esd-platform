import unittest

import journey_timeseries

S = 1_000_000  # 1초(µs)
BASE = 1_800_000_000 * S


def order(number, requested, warmup=False, status=200, responded=None):
    entry = {"orderNo": "o-%d" % number, "requestedEpochUs": BASE + requested, "warmup": warmup, "status": status}
    if responded is not None:
        entry["respondedEpochUs"] = BASE + responded
    return entry


EVIDENCE = {
    "measuredWindow": {"startEpochUs": BASE + 10 * S, "endEpochUs": BASE + 30 * S, "seconds": 20},
    "orders": [
        order(0, 0, warmup=True),            # 예열, 2초에 완료
        order(1, 10 * S),                    # 12초 완료
        order(2, 15 * S),                    # 결제 실패, 18초
        order(3, 20 * S, status=500, responded=20 * S + 300_000),  # HTTP 거절
        order(4, 25 * S),                    # 창 뒤 35초 완료
        order(5, 29 * S),                    # 미해소
    ],
}
OUTCOMES_TEXT = "\n".join([
    "o-0\tPAID\t%d\t%d" % (BASE + 2 * S, BASE + 1 * S),
    "o-1\tPAID\t%d\t%d" % (BASE + 12 * S, BASE + 11 * S),
    "o-2\tFAILED\tNULL\t%d" % (BASE + 18 * S),
    "o-4\tPAID\t%d\t%d" % (BASE + 35 * S, BASE + 26 * S),
    "o-5\tPENDING\tNULL\t%d" % (BASE + 29 * S),
])


class SqlTest(unittest.TestCase):
    def test_reads_db_times_as_utc_epoch_and_rejects_unsafe_order_numbers(self):
        sql = journey_timeseries.outcome_sql(["o-1", "o-2"])
        self.assertTrue(sql.startswith("SET time_zone = '+00:00';"))
        self.assertIn("UNIX_TIMESTAMP(lp.received_at) * 1000000", sql)
        self.assertIn("IN ('o-1','o-2')", sql)
        with self.assertRaises(ValueError):
            journey_timeseries.outcome_sql(["o-1'; DROP"])


class SummaryTest(unittest.TestCase):
    def setUp(self):
        outcomes = journey_timeseries.parse_outcomes(OUTCOMES_TEXT)
        self.result = journey_timeseries.summarize(EVIDENCE, journey_timeseries.requests(EVIDENCE, outcomes))

    def test_series_count_offered_finished_and_outstanding_per_10_seconds(self):
        series = self.result["series"]
        self.assertEqual(series["offered"], [1, 2, 3, 0])
        self.assertEqual(series["completed"], [1, 1, 0, 1])
        self.assertEqual(series["failed"], [0, 1, 0, 0])
        self.assertEqual(series["rejected"], [0, 0, 1, 0])
        # 10초 칸 끝마다: 보낸 수 − 끝난 수(1−1, 3−3, 6−4, 6−5). 마지막 칸에 미해소 o-5 하나가 남는다.
        self.assertEqual(series["outstanding"], [0, 0, 2, 1])
        self.assertEqual((self.result["measure_from_bucket"], self.result["measure_to_bucket"]), (1, 2))

    def test_rates_use_the_fixed_window_and_split_in_window_from_later_completion(self):
        rates = self.result["rates"]
        # 창 [10s, 30s) 안 측정 요청 5건, 완료 1건(o-1, 12s).
        self.assertEqual(rates["offered_per_s"], 5 / 20)
        self.assertEqual(rates["completed_per_s"], 1 / 20)
        self.assertEqual(self.result["measured_orders"], {
            "requested": 5, "completed_in_window": 1, "completed_after_window": 1,
            "failed": 1, "rejected": 1, "unresolved": 1})

    def test_unresolved_orders_mean_no_drain_time(self):
        self.assertEqual(self.result["unresolved"], 1)
        self.assertIsNone(self.result["drain_seconds"])
        self.assertEqual(self.result["clock_check"], {"finish_before_start": 0})

    def test_drain_time_runs_from_the_last_request_to_the_last_finish(self):
        outcomes = journey_timeseries.parse_outcomes(OUTCOMES_TEXT.replace(
            "o-5\tPENDING\tNULL", "o-5\tPAID\t%d" % (BASE + 40 * S)))
        result = journey_timeseries.summarize(EVIDENCE, journey_timeseries.requests(EVIDENCE, outcomes))
        self.assertEqual(result["drain_seconds"], 11.0)


if __name__ == "__main__":
    unittest.main()
