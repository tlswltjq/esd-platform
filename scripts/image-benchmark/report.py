#!/usr/bin/env python3
"""Summarize successful benchmark records; failed attempts never enter aggregates."""

import argparse
import json
from pathlib import Path
import re
import statistics


def summary(values):
    return {"n": len(values), "median": statistics.median(values), "min": min(values), "max": max(values)}


def analyze(directory):
    result = {"session": directory.name, "builds": {}, "images": {}, "runtime": {}}
    source = directory / "source.json"
    if source.exists():
        result["source"] = json.loads(source.read_text())
    environment = directory / "environment.json"
    if environment.exists():
        recorded = json.loads(environment.read_text())
        result["environment"] = {key: recorded[key] for key in ["architecture", "cpu_count", "host_java", "image_java", "base_image_id"]}
    for method in ["baseline", "jib"]:
        file = directory / (method + "-builds.json")
        if not file.exists():
            continue
        records = json.loads(file.read_text())
        result["builds"][method] = {}
        for scenario in ["clean-output", "no-change", "class-change"]:
            selected = [record for record in records if record["scenario"] == scenario]
            if not selected:
                continue
            result["builds"][method][scenario] = {
                "publish_seconds": summary([record["seconds"] for record in selected]),
                "host_busy_cpu_seconds": summary([record["host_busy_cpu_seconds"] for record in selected]),
                "changed_store_compressed_bytes": summary([record["images"]["store"]["changed_compressed_layer_bytes"] for record in selected])}
        canonical = next((record for record in records if record["scenario"] == "no-change" and record["repeat"] == 3), None)
        if canonical:
            images = canonical["images"]
            blobs = {layer["digest"]: layer["size"] for image in images.values() for layer in image["layers"]}
            result["images"][method] = {
                "logical_compressed_bytes_sum": sum(image["compressed_layer_bytes"] for image in images.values()),
                "unique_compressed_blob_bytes": sum(blobs.values()),
                "per_module": {name: {"compressed_bytes": image["compressed_layer_bytes"], "layers": image["layer_count"], "digest": image["digest"]}
                               for name, image in images.items()},
                "unchanged_store_unique_digests": len({record["images"]["store"]["digest"] for record in records if record["scenario"] == "no-change"})}
        runtime = [json.loads(path.read_text()) for path in sorted(directory.glob(method + "-runtime-*.json"))]
        if runtime:
            for record in runtime:
                if record["load"]["failures"] or record["load"]["requests"] != 1200:
                    raise ValueError("Invalid measured runtime sample")
                if any(value <= 0 for value in record["load"]["memory_mean_bytes"].values()):
                    raise ValueError("Missing container memory sample")
            jvm_starts = []
            for record in runtime:
                folder = directory / (method + "-runtime-" + str(record["repeat"]))
                per_app = {}
                for path in folder.glob("*.log"):
                    matches = re.findall(r"Started .+? in ([0-9.]+) seconds \(process running for ([0-9.]+)\)", path.read_text())
                    if matches:
                        per_app[path.stem] = float(matches[-1][1])
                jvm_starts.append(per_app)
            result["runtime"][method] = {
                "startup_all_seconds": summary([record["startup_all_seconds"] for record in runtime]),
                "p50_ms": summary([record["load"]["latency_p50_ms"] for record in runtime]),
                "p95_ms": summary([record["load"]["latency_p95_ms"] for record in runtime]),
                "p99_ms": summary([record["load"]["latency_p99_ms"] for record in runtime]),
                "app_cpu_seconds_60s": summary([sum(record["load"]["cpu_seconds"].values()) for record in runtime]),
                "app_working_set_bytes": summary([sum(record["load"]["memory_mean_bytes"].values()) for record in runtime]),
                "requests": sum(record["load"]["requests"] for record in runtime),
                "failures": sum(record["load"]["failures"] for record in runtime),
                "startup_process_running_seconds": jvm_starts,
                "per_module_cpu_seconds_60s": {name: summary([record["load"]["cpu_seconds"][name] for record in runtime])
                                              for name in runtime[0]["load"]["cpu_seconds"]},
                "per_module_working_set_bytes": {name: summary([record["load"]["memory_mean_bytes"][name] for record in runtime])
                                                for name in runtime[0]["load"]["memory_mean_bytes"]}}
    result["complete"] = all(
        result["builds"].get(method, {}).get(scenario, {}).get("publish_seconds", {}).get("n", 0) == 3
        for method in ["baseline", "jib"] for scenario in ["clean-output", "no-change", "class-change"]
    ) and all(result["runtime"].get(method, {}).get("startup_all_seconds", {}).get("n", 0) >= 2 for method in ["baseline", "jib"])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--print-json", action="store_true")
    args = parser.parse_args()
    result = analyze(args.directory)
    output = args.output or args.directory / "summary.json"
    output.write_text(json.dumps(result, indent=2))
    if args.print_json:
        print(json.dumps(result, indent=2))
    else:
        print("Summary: " + str(output) + "; complete=" + str(result["complete"]))
        for method, scenarios in result["builds"].items():
            for scenario, values in scenarios.items():
                print(method, scenario, "median=" + str(round(values["publish_seconds"]["median"], 3)) + "s")
        for method, values in result["runtime"].items():
            print(method, "runtime n=" + str(values["startup_all_seconds"]["n"]),
                  "p95=" + str(round(values["p95_ms"]["median"], 3)) + "ms")


if __name__ == "__main__":
    main()
