"""격리 스택에서 메시징 비교 실험 한 회차를 돈다(결정 204). ``verify-jib.py --measure``가 부른다.

순서: 부하 드라이버(``:e2e:messagingLoadTest``) → 그 주문번호로 업무 완료 시간 수집 → 같은 주문들의 DB 시각
범위를 창으로 브로커 구간 지연 수집 → 결과를 회차 폴더에 남긴다. 업무 데이터는 수집 단계에서 읽기만 한다.
"""
import json
import sys
from pathlib import Path


def load_command(offset, rate, seconds, buyers, evidence):
    return ["./gradlew", ":e2e:messagingLoadTest", "--no-daemon", "--console=plain",
            "-Dstove.e2e.port-offset=" + str(offset),
            "-Dstove.e2e.load-rate=" + str(rate),
            "-Dstove.e2e.load-duration-secs=" + str(seconds),
            "-Dstove.e2e.load-buyers=" + str(buyers),
            "-Dstove.e2e.load-evidence=" + str(evidence)]


def journey_command(root, container, evidence, out):
    return [sys.executable, "-B", str(Path(root) / "tools/measurement/journey_latency.py"),
            "--container", container, "--evidence", str(evidence), "--out", str(out)]


def broker_command(root, container, since, until, out):
    return [sys.executable, "-B", str(Path(root) / "tools/measurement/broker_latency.py"),
            "--container", container, "--since", since, "--until", until, "--out", str(out)]


def measure(root, docker, run, capture, output, offset, rate, seconds, buyers):
    """한 회차를 돌고 요약을 돌려준다. ``run``·``capture``는 ``verify-jib.py``의 것을 받는다."""
    load = Path(output) / "load.json"
    run(load_command(offset, rate, seconds, buyers, load), "measure-load")
    container = capture(docker + ["ps", "-q", "mysql"])
    journey = Path(output) / "journey.json"
    run(journey_command(root, container, load, journey), "measure-journey")
    window = json.loads(journey.read_text())["window"]
    broker = Path(output) / "broker.json"
    run(broker_command(root, container, window["since"], window["until"], broker), "measure-broker")
    loaded = json.loads(load.read_text())
    http = sorted(order["httpMs"] for order in loaded["orders"] if "httpMs" in order)
    return {"rate": rate, "duration_seconds": seconds, "buyers": buyers,
            "planned": loaded["planned"], "accepted": loaded["accepted"],
            "http_p95_ms": http[max(0, -(-95 * len(http) // 100) - 1)] if http else None,
            "journey": json.loads(journey.read_text())["purchase"],
            "broker": json.loads(broker.read_text())["groups"]}
