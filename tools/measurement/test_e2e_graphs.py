import json
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path

import e2e_graphs

DATA = {"bucket_us": 10_000_000, "measure_from_bucket": 1, "measure_to_bucket": 2,
        "series": {"offered": [20, 20, 20, 0], "completed": [10, 20, 20, 10], "failed": [0, 0, 0, 0],
                   "outstanding": [10, 10, 10, 0]},
        "lag": {"samples": [{"at_us": 0, "total": 3}, {"at_us": 10_000_000, "total": None}, {"at_us": 20_000_000, "total": 0}]}}


class DrawTest(unittest.TestCase):
    def test_writes_throughput_outstanding_and_lag_next_to_the_series(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "timeseries.json"
            path.write_text(json.dumps(DATA), encoding="utf-8")
            self.assertEqual(e2e_graphs.draw(path), ["e2e-lag.svg", "e2e-outstanding.svg", "e2e-throughput.svg"])
            for name in ("e2e-lag.svg", "e2e-outstanding.svg", "e2e-throughput.svg"):
                ElementTree.parse(Path(folder) / name)

    def test_throughput_is_orders_per_second_with_layer_1_labels(self):
        svg = e2e_graphs.throughput_svg(DATA)
        self.assertIn("Offered(보낸 주문)", svg)
        self.assertIn("층 1 처리량", svg)
        # 10초 칸 20건 = 2 주문/s.
        self.assertIn(">2</text>", svg)

    def test_lag_breaks_at_unknown_samples_and_skips_runs_without_samples(self):
        self.assertIn("미관측 표본 1", e2e_graphs.lag_svg(DATA))
        self.assertIsNone(e2e_graphs.lag_svg({"lag": {"samples": []}}))


if __name__ == "__main__":
    unittest.main()
