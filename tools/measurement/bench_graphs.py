#!/usr/bin/env python3
"""층 2 회차 요약(``summary.json``)에서 그래프 SVG를 다시 그린다(결정 233: 손으로 그리지 않는다).

    python3 -B tools/measurement/bench_graphs.py runs/E13/<회차>/summary.json

같은 폴더에 쓴다.
- ``throughput.svg``: 10초 칸별 발행 요청·처리 완료·실패 시도(msg/s). 측정 구간을 음영으로 표시한다.
- ``lag.svg``: 10초마다 잰 소비 지연 합(건). ``run.json``의 ``lag`` 표본이 있을 때만.
- ``latency.svg``: 지연 종류별 백분위 곡선(µs → ms, 로그 축). 표본이 없는 종류는 범례에 "표본 없음"으로 적는다.

외부 라이브러리를 쓰지 않는다(측정 호스트에 matplotlib이 없어도 된다). 색은 고정 순서다 — 같은 지표는 회차가 달라도 같은 색이다.
"""
import json
import math
import sys
from pathlib import Path
from xml.sax.saxutils import escape

WIDTH, HEIGHT = 900, 420
LEFT, RIGHT, TOP, BOTTOM = 70, 190, 30, 50
# 색약에서도 구분되는 고정 팔레트(Okabe-Ito 계열).
COLORS = ["#0072B2", "#D55E00", "#009E73", "#CC79A7", "#E69F00", "#56B4E9", "#000000"]
THROUGHPUT_SERIES = [("requested", "발행 요청"), ("completed", "처리 완료"), ("failed_attempts", "실패 시도")]
LATENCY_KINDS = [("e2e_no_retry", "메시징 종단(재시도 없음)"), ("e2e_with_retry", "재시도 포함 완료"),
                 ("e2e_all", "발행→완료(전체)"), ("ack", "발행 확인"), ("broker", "브로커 구간"),
                 ("processing", "처리"), ("final_failure", "최종 실패 판정")]


def nice_max(value):
    """축 위 끝: 1·2·5 × 10^n 중 value 이상인 가장 작은 값. 0이면 1."""
    if value <= 0:
        return 1
    exponent = math.floor(math.log10(value))
    for step in (1, 2, 5, 10):
        candidate = step * 10 ** exponent
        if candidate >= value:
            return candidate
    return 10 ** (exponent + 1)


def _svg(title, body):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
            'font-family="sans-serif" font-size="12">\n<rect width="100%%" height="100%%" fill="#ffffff"/>\n'
            '<text x="%d" y="18" font-size="14" font-weight="bold">%s</text>\n%s</svg>\n'
            % (WIDTH, HEIGHT, WIDTH, HEIGHT, LEFT, escape(title), "\n".join(body)))


def _legend(entries):
    body = []
    for index, (label, color) in enumerate(entries):
        y = TOP + 10 + index * 18
        x = WIDTH - RIGHT + 15
        body.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="2"/>' % (x, y, x + 18, y, color))
        body.append('<text x="%d" y="%d">%s</text>' % (x + 24, y + 4, escape(label)))
    return body


def throughput_svg(summary):
    series = summary.get("timeseries_10s") or {}
    bucket_s = (series.get("bucket_us") or 10_000_000) / 1e6
    columns = [(name, label, [count / bucket_s for count in series.get(name, [])]) for name, label in THROUGHPUT_SERIES]
    buckets = max((len(values) for _, _, values in columns), default=0)
    plot_w, plot_h = WIDTH - LEFT - RIGHT, HEIGHT - TOP - BOTTOM
    top = nice_max(max((v for _, _, values in columns for v in values), default=0))
    body = []

    def x_of(index):
        return LEFT + (plot_w * index / max(1, buckets - 1) if buckets > 1 else plot_w / 2)

    def y_of(value):
        return TOP + plot_h - plot_h * value / top

    start, end = series.get("measure_from_bucket"), series.get("measure_to_bucket")
    if start is not None and end is not None and buckets:
        body.append('<rect x="%.1f" y="%d" width="%.1f" height="%d" fill="#eeeeee"/>'
                    % (x_of(start), TOP, max(1.0, x_of(end) - x_of(start)), plot_h))
    body.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#333"/>' % (LEFT, TOP + plot_h, LEFT + plot_w, TOP + plot_h))
    body.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#333"/>' % (LEFT, TOP, LEFT, TOP + plot_h))
    for tick in range(5):
        value = top * tick / 4
        y = y_of(value)
        body.append('<text x="%d" y="%.1f" text-anchor="end">%g</text>' % (LEFT - 6, y + 4, round(value, 2)))
        body.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#dddddd"/>' % (LEFT, y, LEFT + plot_w, y))
    for index in range(0, buckets, max(1, buckets // 10)):
        body.append('<text x="%.1f" y="%d" text-anchor="middle">%d</text>'
                    % (x_of(index), TOP + plot_h + 16, int(index * bucket_s)))
    body.append('<text x="%d" y="%d" text-anchor="middle">회차 시작 뒤 초(음영: 측정 구간)</text>'
                % (LEFT + plot_w // 2, HEIGHT - 12))
    body.append('<text x="14" y="%d" transform="rotate(-90 14 %d)" text-anchor="middle">msg/s</text>'
                % (TOP + plot_h // 2, TOP + plot_h // 2))
    legend = []
    for (name, label, values), color in zip(columns, COLORS):
        if values:
            points = " ".join("%.1f,%.1f" % (x_of(i), y_of(v)) for i, v in enumerate(values))
            body.append('<polyline fill="none" stroke="%s" stroke-width="2" points="%s"/>' % (color, points))
        legend.append((label if values else label + " (표본 없음)", color))
    body += _legend(legend)
    return _svg("처리량 (10초 칸)", body)


def latency_svg(summary):
    latency = summary.get("latency_us") or {}
    curves = []
    for (name, label), color in zip(LATENCY_KINDS, COLORS):
        points = [(p, v / 1000.0) for p, v in (latency.get(name) or {}).get("curve", [])]
        curves.append((label, color, points, (latency.get(name) or {}).get("count", 0)))
    values = [v for _, _, points, _ in curves for _, v in points if v > 0]
    low = 10 ** math.floor(math.log10(min(values))) if values else 0.001
    high = nice_max(max(values)) if values else 1
    if high <= low:
        high = low * 10
    plot_w, plot_h = WIDTH - LEFT - RIGHT, HEIGHT - TOP - BOTTOM
    # x는 백분위 순서(등간격 범주). 꼬리(99·99.9)가 눌리지 않게 값 대신 순번으로 놓는다.
    percentiles = sorted({p for _, _, points, _ in curves for p, _ in points})

    def x_of(p):
        index = percentiles.index(p)
        return LEFT + plot_w * index / max(1, len(percentiles) - 1)

    def y_of(value):
        value = max(value, low)
        return TOP + plot_h - plot_h * (math.log10(value) - math.log10(low)) / (math.log10(high) - math.log10(low))

    body = ['<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#333"/>' % (LEFT, TOP + plot_h, LEFT + plot_w, TOP + plot_h),
            '<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#333"/>' % (LEFT, TOP, LEFT, TOP + plot_h)]
    decade = low
    while decade <= high * 1.0001:
        y = y_of(decade)
        body.append('<text x="%d" y="%.1f" text-anchor="end">%g</text>' % (LEFT - 6, y + 4, decade))
        body.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#dddddd"/>' % (LEFT, y, LEFT + plot_w, y))
        decade *= 10
    for p in percentiles:
        body.append('<text x="%.1f" y="%d" text-anchor="middle">p%g</text>' % (x_of(p), TOP + plot_h + 16, p))
    body.append('<text x="14" y="%d" transform="rotate(-90 14 %d)" text-anchor="middle">ms (로그)</text>'
                % (TOP + plot_h // 2, TOP + plot_h // 2))
    legend = []
    for label, color, points, count in curves:
        if points:
            body.append('<polyline fill="none" stroke="%s" stroke-width="2" points="%s"/>'
                        % (color, " ".join("%.1f,%.1f" % (x_of(p), y_of(v)) for p, v in points)))
            legend.append(("%s n=%d" % (label, count), color))
        else:
            legend.append((label + " (표본 없음)", color))
    body += _legend(legend)
    return _svg("지연 백분위", body)


def lag_svg(samples):
    """``[{"at_us": .., "total": ..}, ...]`` → 소비 지연 합 선 그래프. 표본이 없으면 None."""
    readings = [(s["at_us"], s["total"]) for s in samples if s.get("total") is not None]
    if not readings:
        return None
    origin = readings[0][0]
    span = max(1.0, (readings[-1][0] - origin) / 1e6)
    top = nice_max(max(total for _, total in readings))
    plot_w, plot_h = WIDTH - LEFT - RIGHT, HEIGHT - TOP - BOTTOM

    def x_of(at_us):
        return LEFT + plot_w * ((at_us - origin) / 1e6) / span

    def y_of(value):
        return TOP + plot_h - plot_h * value / top

    body = ['<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#333"/>' % (LEFT, TOP + plot_h, LEFT + plot_w, TOP + plot_h),
            '<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#333"/>' % (LEFT, TOP, LEFT, TOP + plot_h)]
    for tick in range(5):
        value = top * tick / 4
        body.append('<text x="%d" y="%.1f" text-anchor="end">%g</text>' % (LEFT - 6, y_of(value) + 4, round(value, 2)))
    for tick in range(6):
        seconds = span * tick / 5
        body.append('<text x="%.1f" y="%d" text-anchor="middle">%d</text>'
                    % (LEFT + plot_w * tick / 5, TOP + plot_h + 16, int(seconds)))
    body.append('<text x="%d" y="%d" text-anchor="middle">첫 표본 뒤 초</text>' % (LEFT + plot_w // 2, HEIGHT - 12))
    body.append('<polyline fill="none" stroke="%s" stroke-width="2" points="%s"/>'
                % (COLORS[0], " ".join("%.1f,%.1f" % (x_of(at), y_of(total)) for at, total in readings)))
    body += _legend([("소비 지연 합(건)", COLORS[0])])
    return _svg("소비 지연", body)


def draw(summary_path):
    summary_path = Path(summary_path)
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    outputs = {"throughput.svg": throughput_svg(summary), "latency.svg": latency_svg(summary)}
    lag = lag_svg((summary.get("run") or {}).get("lag") or [])
    if lag is not None:
        outputs["lag.svg"] = lag
    for name, svg in outputs.items():
        (summary_path.parent / name).write_text(svg, encoding="utf-8")
    return sorted(outputs)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        sys.stderr.write(__doc__)
        return 2
    print(" ".join(draw(argv[0])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
