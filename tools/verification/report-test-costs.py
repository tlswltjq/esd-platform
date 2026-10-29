#!/usr/bin/env python3
# coding: utf-8
"""Summarize existing JUnit XML and Gradle logs. Never runs tests or changes their policy."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def migration_candidate(class_name: str, test_name: str) -> bool:
    """Cost triage only: parameterized display names may omit the original method name."""
    return (
        class_name.endswith("MigrationIntegrationTest")
        or "migrat" in test_name.lower()
        or re.match(r"^v\d+[A-Z]", test_name) is not None
        or (class_name.endswith("RefundExecutionIntegrationTest") and "evidence=" in test_name)
    )


def summarize(label: str, directory: Path) -> dict:
    files = sorted(directory.glob("TEST-*.xml"))
    if not files:
        raise ValueError(f"No JUnit XML reports: {directory}")
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    cases = []
    suites = []
    startups = []
    container_starts = []
    migrations = 0
    sources = {}
    for path in files:
        data = path.read_bytes()
        root = ET.fromstring(data)
        if root.tag != "testsuite":
            raise ValueError(f"Expected Gradle testsuite XML: {path}")
        sources[path.name] = hashlib.sha256(data).hexdigest()
        name = root.get("name", path.stem)
        for key in totals:
            totals[key] += int(root.get(key, "0"))
        case_seconds = 0.0
        for case in root.findall("testcase"):
            seconds = float(case.get("time", "0"))
            case_seconds += seconds
            test_name = case.get("name", "")
            cases.append({"class": name, "test": test_name, "seconds": seconds,
                          "migration_candidate": migration_candidate(name, test_name),
                          "failed": case.find("failure") is not None or case.find("error") is not None,
                          "skipped": case.find("skipped") is not None})
        suites.append({"class": name, "suite_seconds": float(root.get("time", "0")),
                       "testcase_seconds": round(case_seconds, 6), "tests": int(root.get("tests", "0"))})
        output = root.findtext("system-out", "")
        startups.extend({"class": match.group(1), "seconds": float(match.group(2))}
                        for match in re.finditer(r"Started (\S+) in ([\d.]+) seconds", output))
        container_starts.extend(re.findall(r"Container (\S+) started in", output))
        migrations += len(re.findall(r"Migrating schema `", output))
    seconds = sum(row["seconds"] for row in cases)
    migration_cases = [row for row in cases if row["migration_candidate"]]
    migration_seconds = sum(row["seconds"] for row in migration_cases)
    return {
        "label": label, "directory": str(directory), "counts": totals,
        "suite_count": len(suites), "suite_seconds": round(sum(row["suite_seconds"] for row in suites), 6),
        "testcase_seconds": round(seconds, 6),
        "migration_candidate_count": len(migration_cases), "migration_candidate_seconds": round(migration_seconds, 6),
        "migration_candidate_percent_of_testcase_seconds": round(100 * migration_seconds / seconds, 3) if seconds else None,
        "spring_startup_count": len(startups), "spring_startup_logged_seconds": round(sum(row["seconds"] for row in startups), 6),
        "logged_migration_steps": migrations, "logged_container_starts": container_starts,
        "slowest_cases": sorted(cases, key=lambda row: row["seconds"], reverse=True),
        "suites": sorted(suites, key=lambda row: row["suite_seconds"], reverse=True),
        "spring_startups": startups, "migration_candidates": migration_cases, "source_sha256": sources,
    }


def build_log(path: Path) -> dict:
    data = path.read_bytes()
    matches = re.findall(r"BUILD (SUCCESSFUL|FAILED) in ([^\n]+)", data.decode("utf-8", errors="replace"))
    result = {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}
    if matches:
        state, duration = matches[-1]
        seconds = sum(float(number) * {"h": 3600, "m": 60, "s": 1, "ms": 0.001}[unit]
                      for number, unit in re.findall(r"(\d+(?:\.\d+)?)(ms|h|m|s)", duration))
        result.update({"result": state, "duration": duration, "command_elapsed_seconds": seconds})
    else:
        result["result"] = "NO_COMPLETION_MARKER"
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", action="append", required=True, metavar="LABEL=DIRECTORY")
    parser.add_argument("--build-log", action="append", default=[], type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        suites = []
        for value in args.suite:
            label, separator, directory = value.partition("=")
            if not separator or not label or not directory:
                raise ValueError("--suite requires LABEL=DIRECTORY")
            suites.append(summarize(label, Path(directory)))
        labels = [row["label"] for row in suites]
        if len(labels) != len(set(labels)):
            raise ValueError("Suite labels must be unique")
        logs = [build_log(path) for path in args.build_log]
        result = {"kind": "historical_report_analysis", "new_tests_executed": False,
                  "limits": ["JUnit case times are not Gradle wall-clock elapsed time.",
                             "Spring startup times are logged observations, not additive independent samples.",
                             "Migration candidates are a heuristic for review, never an automatic test exclusion.",
                             "Command durations can overlap; do not sum them as user wait time.",
                             "Archived reports may come from different successful executions; paths and hashes identify evidence."],
                  "suites": suites, "build_logs": logs}
        serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(serialized, encoding="utf-8")
        for row in suites:
            print(f"{row['label']}: {row['counts']['tests']} cases, {row['testcase_seconds']:.3f}s case total; "
                  f"migration candidates {row['migration_candidate_count']} / {row['migration_candidate_seconds']:.3f}s "
                  f"({row['migration_candidate_percent_of_testcase_seconds']}%); "
                  f"Spring starts {row['spring_startup_count']}")
        if args.output:
            print(f"Historical analysis saved: {args.output}")
        return 0
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Cannot analyze reports: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
