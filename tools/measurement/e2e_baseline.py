#!/usr/bin/env python3
"""Kafka 기준선 층 1(E2E) 회차들을 돈다(E13·결정 230·231). 한 회차 = ``verify-jib.py --measure`` 한 번 = 새 격리 스택 하나.

    # 주문률 단계 탐색(결정 230: 2 → 5 → 10 → 20 → 40/s, 단계마다 1회)
    python3 -B tools/measurement/e2e_baseline.py stages --series explore-1 --rates 2,5,10,20,40 --seconds 180 --warmup 30
    # 장애 회차(시나리오 3~6)
    python3 -B tools/measurement/e2e_baseline.py stages --series s3-r1 --rates 2 --seconds 300 --warmup 30 \\
        --fault db-revoke:at=120,for=3,table=stove_license.product_license
    # 계측 확인(결정 230 Smoke): 저부하 정상 회차 1번 + 장애 4종을 한 번씩 넣은 회차 1번
    python3 -B tools/measurement/e2e_baseline.py smoke

회차마다 이전 회차의 메시지·offset·재시도 상태·업무 데이터가 섞이지 않게 스택을 새로 띄운다(결정 230). 회차 전후를
``measure_guard.py``로 감싼다. 진행 상태는 ``runs/E13/<series>/state.json``에 남겨, 끊기면 끝난 회차를 건너뛰고 그 회차만 다시
돈다. 회차 결과(verify-jib 의 ``result.json``·``load.json``·``timeseries.json`` 등)는 verify-jib 가 만든 폴더에 있고, 여기서는
그 경로와 요약만 모으며 ``e2e_graphs.py``로 그래프를 그린다. 판정(결정 231)은 하지 않는다.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GUARD_ALLOW = "messaging-measure-"
_SERIES = re.compile(r"^[a-z0-9][a-z0-9.-]{0,40}$")
# 장애 4종을 한 회차에 겹치지 않게 넣는다(첫 주문 기준 초). 각 장애의 흔적이 다른 소비 그룹·토픽에 남아 서로 구분된다.
SMOKE_FAULTS = [
    "db-revoke:at=40,for=3,table=stove_license.product_license",   # 재시도 창보다 짧다 → 재시도 뒤 완료
    "malformed:at=60,topic=stove.order.v1,count=1",                 # 그 토픽 소비 그룹마다 격리
    "kill:at=80,for=10,service=settlement",                         # 정산 소비자 재시작
    "dlt-limit:at=100,for=15,limit=512",                            # 결정 235 Store DLT 쓰기 거부 → 복구 뒤 단일 저장
]


def rates(text):
    values = [float(value) for value in text.split(",") if value.strip()]
    if not values or any(value <= 0 for value in values):
        raise argparse.ArgumentTypeError("주문률은 0보다 큰 수의 쉼표 목록이다: " + text)
    return values


def stage_name(rate):
    return ("r%g" % rate).replace(".", "_")


def verify_command(options, rate, skip_build):
    command = [sys.executable, "-B", str(ROOT / "tools/verification/verify-jib.py"), "--measure",
               "--load-rate", "%g" % rate, "--load-duration", str(options.seconds), "--load-warmup", str(options.warmup),
               "--load-buyers", str(options.buyers)]
    for spec in options.fault or []:
        command += ["--fault", spec]
    if skip_build:
        command.append("--skip-build")
    return command


def evidence_path(log_text):
    matched = re.search(r"^Evidence: (.+)$", log_text, re.M)
    return matched.group(1).strip() if matched else None


def stage_summary(result):
    """verify-jib ``result.json`` → 단계 한 줄 요약(판정 없이 값만)."""
    measurement = result.get("measurement") or {}
    series = measurement.get("timeseries") or {}
    journey = measurement.get("journey") or {}
    return {"complete": result.get("complete", False), "error": result.get("error"),
            "valid": (measurement.get("validity") or {}).get("valid"),
            "reasons": (measurement.get("validity") or {}).get("reasons"),
            "outcome": (measurement.get("validity") or {}).get("outcome"),
            "achieved_rate": (measurement.get("generator") or {}).get("achievedRate"),
            "rates": series.get("rates"), "drain_seconds": series.get("drain_seconds"),
            "unresolved": series.get("unresolved"),
            "paid_to_basis": journey.get("paid_to_basis"),
            "faults": [{"kind": fault["kind"], "problems": fault["problems"]}
                       for fault in ((measurement.get("faults") or {}).get("faults") or [])]}


def load_state(path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"stages": {}}


def run_stages(options):
    if not _SERIES.match(options.series):
        raise SystemExit("계열 이름은 소문자·숫자·점·하이픈 40자 이하다: " + options.series)
    series_dir = Path(options.run_root) / options.series
    series_dir.mkdir(parents=True, exist_ok=True)
    state_file = series_dir / "state.json"
    state = load_state(state_file)
    state.update({"rates": options.rates, "seconds": options.seconds, "warmup": options.warmup,
                  "buyers": options.buyers, "faults": options.fault or []})
    built = options.skip_build
    failures = 0
    for rate in options.rates:
        name = stage_name(rate)
        previous = state["stages"].get(name)
        if previous and previous.get("summary", {}).get("complete"):
            print("건너뜀(끝난 회차): %s %s" % (name, previous.get("evidence")), flush=True)
            continue
        guard = "%s-%s" % (options.series, name)
        subprocess.run([sys.executable, str(ROOT / "tools/ci/measure_guard.py"), "before", "--name", guard,
                        "--allow", GUARD_ALLOW], cwd=ROOT, check=True)
        log_file = series_dir / (name + ".log")
        started = time.time()
        with log_file.open("w", encoding="utf-8") as log:
            completed = subprocess.run(verify_command(options, rate, built), cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        built = True  # 첫 회차가 이미지를 만들었다. 같은 커밋이면 다시 만들지 않는다.
        guard_result = subprocess.run([sys.executable, str(ROOT / "tools/ci/measure_guard.py"), "after", "--name", guard],
                                      cwd=ROOT, capture_output=True, text=True)
        evidence = evidence_path(log_file.read_text(encoding="utf-8"))
        result_file = Path(evidence) / "result.json" if evidence else None
        summary = stage_summary(json.loads(result_file.read_text())) if result_file and result_file.exists() else {
            "complete": False, "error": "result.json 이 없다(verify-jib 종료 %d)" % completed.returncode}
        summary["exit_code"] = completed.returncode
        summary["guard"] = {"exit_code": guard_result.returncode, "output": guard_result.stdout.strip()[-500:]}
        if guard_result.returncode != 0:
            summary["complete"] = False
        state["stages"][name] = {"rate": rate, "evidence": evidence, "seconds_taken": round(time.time() - started),
                                 "summary": summary}
        state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        if evidence and (Path(evidence) / "timeseries.json").exists():
            subprocess.run([sys.executable, "-B", str(ROOT / "tools/measurement/e2e_graphs.py"),
                            str(Path(evidence) / "timeseries.json")], cwd=ROOT, check=False)
        ok = completed.returncode == 0 and summary["complete"]
        print("%s: %s %s" % (name, "끝남" if ok else "실패", evidence), flush=True)
        if not ok:
            failures += 1
            if guard_result.returncode != 0:
                # 다른 컨테이너가 남거나 사라졌다. 다음 회차를 돌리지 않는다(AGENTS 5절 격리·보존).
                print(guard_result.stdout, flush=True)
                break
    return 1 if failures else 0


def smoke_problems(state):
    """smoke 판정: 정상 회차는 유효·해소, 장애 회차는 네 장애가 모두 계획대로 일어나고 되돌려졌는가."""
    problems = []
    normal = state.get("normal", {}).get("summary", {})
    if not normal.get("complete") or not normal.get("valid"):
        problems.append("정상 회차 무효: %s %s" % (normal.get("error"), normal.get("reasons")))
    if normal.get("unresolved"):
        problems.append("정상 회차 미해소 %s건" % normal["unresolved"])
    faulted = state.get("faults", {}).get("summary", {})
    kinds = [fault["kind"] for fault in faulted.get("faults") or []]
    if sorted(kinds) != sorted(spec.split(":", 1)[0] for spec in SMOKE_FAULTS):
        problems.append("장애 회차에 계획한 장애가 다 없다: %s" % kinds)
    for fault in faulted.get("faults") or []:
        problems += ["%s: %s" % (fault["kind"], problem) for problem in fault["problems"]]
    if not faulted.get("complete") or not faulted.get("valid"):
        problems.append("장애 회차 무효: %s %s" % (faulted.get("error"), faulted.get("reasons")))
    return problems


def smoke(options):
    stamp = time.strftime("%Y%m%d%H%M%S")
    results = {}
    for label, faults in (("normal", []), ("faults", SMOKE_FAULTS)):
        stage_options = argparse.Namespace(series="smoke-%s-%s" % (label, stamp), rates=[2.0], seconds=120, warmup=10,
                                           buyers=20, fault=faults, run_root=options.run_root,
                                           skip_build=options.skip_build or label == "faults")
        run_stages(stage_options)
        stage = load_state(Path(options.run_root) / stage_options.series / "state.json")["stages"].get(stage_name(2.0), {})
        results[label] = stage
    problems = smoke_problems(results)
    for label, stage in results.items():
        print("%s: %s" % (label, stage.get("evidence")))
    print("smoke: %s" % ("통과" if not problems else "실패"))
    for problem in problems:
        print("  - " + problem)
    return 1 if problems else 0


def parser():
    root = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = root.add_subparsers(dest="command", required=True)
    stages = commands.add_parser("stages")
    stages.add_argument("--series", required=True)
    stages.add_argument("--rates", type=rates, required=True, help="쉼표로 나눈 주문률(초당 주문)")
    stages.add_argument("--seconds", type=int, required=True)
    stages.add_argument("--warmup", type=int, default=30)
    stages.add_argument("--buyers", type=int, default=20)
    stages.add_argument("--fault", action="append", default=[], help="verify-jib.py --fault 와 같은 형식(여러 번)")
    for command in (stages, commands.add_parser("smoke")):
        command.add_argument("--run-root", default=str(ROOT / "runs" / "E13"))
        command.add_argument("--skip-build", action="store_true", help="이미 만든 이미지를 쓴다")
    return root


def main(argv=None):
    options = parser().parse_args(argv)
    return smoke(options) if options.command == "smoke" else run_stages(options)


if __name__ == "__main__":
    sys.exit(main())
