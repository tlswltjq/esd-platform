#!/usr/bin/env python3
"""층 1 회차의 ``timeseries.json``(``journey_timeseries.py`` + 소비 지연 표본)에서 그래프 SVG를 다시 그린다(결정 233).

    python3 -B tools/measurement/e2e_graphs.py <회차 폴더>/timeseries.json

같은 폴더에 쓴다.
- ``e2e-throughput.svg``: 10초 칸별 Offered·Completed(주문/s). 측정 구간을 음영으로 표시한다.
- ``e2e-outstanding.svg``: 10초 칸 끝의 Outstanding(건).
- ``e2e-lag.svg``: 브로커에서 읽은 소비 지연 합(건). 표본이 있을 때만. 모르는 표본에서는 선을 끊는다.
그리는 방식은 층 2(``bench_graphs.py``)와 같다.
"""
import json
import sys
from pathlib import Path

import bench_graphs


THROUGHPUT = [("offered", "Offered(보낸 주문)"), ("completed", "Completed(구매 근거)"), ("failed", "결제 실패")]


def throughput_svg(data):
    series = data.get("series") or {}
    summary = {"timeseries_10s": dict({name: series.get(name, []) for name, _ in THROUGHPUT},
                                      bucket_us=data.get("bucket_us"), measure_from_bucket=data.get("measure_from_bucket"),
                                      measure_to_bucket=data.get("measure_to_bucket"))}
    return bench_graphs.throughput_svg(summary, THROUGHPUT, "층 1 처리량 (10초 칸, 주문/s)")


def outstanding_svg(data):
    bucket_us = data.get("bucket_us") or 10_000_000
    samples = [{"at_us": index * bucket_us, "total": value}
               for index, value in enumerate((data.get("series") or {}).get("outstanding", []))]
    return bench_graphs.lag_svg(samples, "Outstanding(건, 10초 칸 끝)", "Outstanding")


def lag_svg(data):
    samples = ((data.get("lag") or {}).get("samples")) or []
    return bench_graphs.lag_svg([{"at_us": sample["at_us"], "total": sample.get("total")} for sample in samples],
                                "소비 지연 합(건, 브로커 커밋 offset 기준)", "층 1 소비 지연")


def draw(path):
    path = Path(path)
    data = json.loads(path.read_text(encoding="utf-8"))
    outputs = {"e2e-throughput.svg": throughput_svg(data), "e2e-outstanding.svg": outstanding_svg(data),
               "e2e-lag.svg": lag_svg(data)}
    written = []
    for name, svg in outputs.items():
        if svg is not None:
            (path.parent / name).write_text(svg, encoding="utf-8")
            written.append(name)
    return sorted(written)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        sys.stderr.write(__doc__)
        return 2
    print(" ".join(draw(argv[0])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
