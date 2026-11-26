import json
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path

import bench_graphs

SUMMARY = {
    "timeseries_10s": {"bucket_us": 10_000_000, "measure_from_bucket": 1, "measure_to_bucket": 2,
                       "requested": [100, 1000, 1000, 0], "completed": [90, 1000, 1000, 10], "failed_attempts": [0, 5, 0, 0]},
    "latency_us": {
        "e2e_no_retry": {"count": 3000, "curve": [[0, 900], [50, 2000], [99, 9000], [100, 30000]]},
        "e2e_with_retry": {"count": 0, "curve": []},
    },
    "run": {"lag": [{"at_us": 0, "total": 0}, {"at_us": 10_000_000, "total": 40}, {"at_us": 20_000_000, "total": 0}]},
}


class NiceMaxTest(unittest.TestCase):
    def test_rounds_up_to_one_two_five(self):
        self.assertEqual(bench_graphs.nice_max(0), 1)
        self.assertEqual(bench_graphs.nice_max(7), 10)
        self.assertEqual(bench_graphs.nice_max(130), 200)
        self.assertEqual(bench_graphs.nice_max(400), 500)
        self.assertEqual(bench_graphs.nice_max(1000), 1000)


class DrawTest(unittest.TestCase):
    def test_writes_valid_svgs_next_to_the_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "summary.json"
            path.write_text(json.dumps(SUMMARY), encoding="utf-8")
            self.assertEqual(bench_graphs.draw(path), ["lag.svg", "latency.svg", "throughput.svg"])
            for name in ("lag.svg", "latency.svg", "throughput.svg"):
                ElementTree.parse(Path(folder) / name)  # 깨진 XML이면 여기서 실패한다

    def test_throughput_is_per_second_and_shades_the_measured_window(self):
        svg = bench_graphs.throughput_svg(SUMMARY)
        # 10초 칸 1000건 = 100 msg/s 이므로 축 위 끝이 100이다.
        self.assertIn(">100</text>", svg)
        self.assertIn('fill="#eeeeee"', svg)

    def test_latency_names_kinds_without_samples_instead_of_drawing_zero(self):
        svg = bench_graphs.latency_svg(SUMMARY)
        self.assertIn("재시도 포함 완료 (표본 없음)", svg)
        self.assertIn("메시징 종단(재시도 없음) n=3000", svg)
        self.assertEqual(svg.count("<polyline"), 1)

    def test_lag_graph_needs_samples(self):
        self.assertIsNone(bench_graphs.lag_svg([]))
        self.assertIn("<polyline", bench_graphs.lag_svg(SUMMARY["run"]["lag"]))


if __name__ == "__main__":
    unittest.main()
