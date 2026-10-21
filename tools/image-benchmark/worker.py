#!/usr/bin/env python3
"""Remote worker; all resources are owned by one benchmark run."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import resource
import shutil
import signal
import subprocess
import time
import urllib.request


MODULES = {"auth": 8091, "gateway": 8080, "studio": 8085, "review": 8086,
           "catalog": 8081, "store": 8087, "order": 8082, "payment": 8083,
           "license": 8084, "download": 8088, "settlement": 8089, "mock-pg": 8092}


def module_path(name):
    return ("simulators" if name == "mock-pg" else "apps") + "/" + name


def capture(args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()


def cpu_seconds():
    values = [int(value) for value in Path("/proc/stat").read_text().splitlines()[0].split()[1:9]]
    return (sum(values) - values[3] - values[4]) / os.sysconf("SC_CLK_TCK")


def run(args, cwd, log, environment=None):
    before = resource.getrusage(resource.RUSAGE_CHILDREN)
    cpu = cpu_seconds()
    started = time.monotonic()
    with open(log, "w") as output:
        process = subprocess.run(args, cwd=cwd, env=environment, stdout=output, stderr=subprocess.STDOUT)
    after = resource.getrusage(resource.RUSAGE_CHILDREN)
    result = {"command": args, "seconds": time.monotonic() - started,
              "host_busy_cpu_seconds": cpu_seconds() - cpu,
              "child_user_seconds": after.ru_utime - before.ru_utime,
              "child_system_seconds": after.ru_stime - before.ru_stime,
              "exit_code": process.returncode, "log": str(log)}
    if process.returncode:
        print(Path(log).read_text()[-9000:], flush=True)
        raise RuntimeError("Command failed: " + str(args))
    return result


def request_json(url):
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.manifest.v1+json"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def image_record(registry, repository, tag):
    request = urllib.request.Request("http://" + registry + "/v2/" + repository + "/manifests/" + tag,
        headers={"Accept": "application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.manifest.v1+json"})
    with urllib.request.urlopen(request, timeout=15) as response:
        manifest = json.load(response)
        digest = response.headers["Docker-Content-Digest"]
    layers = manifest.get("layers", [])
    return {"layers": layers, "config": manifest["config"],
            "compressed_layer_bytes": sum(layer["size"] for layer in layers),
            "layer_count": len(layers),
            "digest": digest}


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2))
    temporary.replace(path)


def prepare(root):
    results = root / "results"
    baseline = root / "baseline"
    baseline.mkdir(exist_ok=True)
    subprocess.run(["tar", "-xf", str(root / "baseline.tar"), "-C", str(baseline)], check=True)
    shutil.copytree(baseline, root / "jib", dirs_exist_ok=True)
    stamp = root.name.removeprefix("image-benchmark-") if hasattr(str, "removeprefix") else root.name.replace("image-benchmark-", "", 1)
    prefix = "stove-imgbench-" + stamp.lower()
    # A loopback-only registry measures publication without exposing an unauthenticated service.
    registry = "127.0.0.1:15199"
    subprocess.run(["docker", "run", "-d", "--name", prefix + "-registry", "--label", "stove.image-benchmark=" + stamp,
                    "-p", registry + ":5000", "registry:2"], check=True)
    for attempt in range(60):
        try:
            request_json("http://" + registry + "/v2/")
            break
        except Exception:
            time.sleep(1)
    run(["docker", "pull", "eclipse-temurin:21-jre-alpine"], baseline, results / "base-pull.log")
    base = json.loads(capture(["docker", "image", "inspect", "eclipse-temurin:21-jre-alpine"]))[0]
    local_base = registry + "/benchmark/java21:base"
    subprocess.run(["docker", "tag", base["Id"], local_base], check=True)
    run(["docker", "push", local_base], baseline, results / "base-push.log")
    # Registry returns the canonical content digest; never derive it from reserialized JSON.
    req = urllib.request.Request("http://" + registry + "/v2/benchmark/java21/manifests/base",
        headers={"Accept": "application/vnd.docker.distribution.manifest.v2+json"})
    with urllib.request.urlopen(req) as response:
        pinned_base = registry + "/benchmark/java21@" + response.headers["Docker-Content-Digest"]
    user = capture(["docker", "run", "--rm", "--entrypoint", "sh", base["Id"], "-c",
                    "addgroup -S stove && adduser -S stove -G stove && id stove"])
    state = {"registry": registry, "prefix": prefix, "base": pinned_base, "base_original": base["RepoDigests"],
             "base_image_id": base["Id"], "runtime_user": user, "modules": MODULES,
             "architecture": platform.machine(), "cpu_count": os.cpu_count(),
             "docker": json.loads(capture(["docker", "version", "--format", "{{json .Server}}"])),
             "host_java": capture(["java", "-version"]),
             "image_java": capture(["docker", "run", "--rm", "--entrypoint", "java", base["Id"], "-version"]),
             "memory": capture(["free", "-b"]), "started_utc": datetime.now(timezone.utc).isoformat()}
    save(root / "state.json", state)
    save(results / "environment.json", state)
    # Only the temporary benchmark Dockerfiles change FROM; production source stays untouched.
    for name in MODULES:
        path = module_path(name)
        text = (baseline / path / "Dockerfile").read_text().replace(
            "FROM eclipse-temurin:21-jre-alpine", "FROM " + pinned_base)
        (results / (name + ".Dockerfile")).write_text(text)
    run(["./gradlew", "bootJar", ":e2e:compileTestJava", "--console=plain", "--no-daemon"],
        baseline, results / "baseline-initial-compile.log")
    print("PREPARED " + str(root), flush=True)


def build(root, method):
    state = json.loads((root / "state.json").read_text())
    results = root / "results"
    source = root / method
    registry = state["registry"]
    records = []
    report = results / (method + "-builds.json")
    if report.exists():
        records = json.loads(report.read_text())
    gradle = ["./gradlew", "--console=plain", "--no-daemon"]
    if method == "jib":
        # Resolve plugins before timing so downloading a tool is not an application build result.
        run(gradle + ["help"], source, results / "jib-plugin-resolution.log")
    for scenario in ["clean-output", "no-change", "class-change"]:
        for repeat in range(1, 4):
            label = method + "-" + scenario + "-" + str(repeat)
            if any(record["label"] == label for record in records):
                continue
            print("BUILD " + label, flush=True)
            names = list(MODULES) if scenario != "class-change" else ["store"]
            if scenario == "clean-output":
                run(gradle + ["clean"], source, results / (label + "-clean.log"))
            marker = source / "apps/store/src/main/java/com/stove/store/config/ImageBuildBenchmarkMarker.java"
            if scenario == "class-change":
                marker.parent.mkdir(parents=True, exist_ok=True)
                marker.write_text("package com.stove.store.config;\nfinal class ImageBuildBenchmarkMarker {\n"
                                  "    static final String REVISION = \"benchmark-" + str(repeat) + "\";\n"
                                  "    private ImageBuildBenchmarkMarker() {}\n}\n")
            before = {}
            for name in names:
                repo = "benchmark/" + method + "/" + name
                try:
                    before[name] = image_record(registry, repo, "current")
                except Exception:
                    before[name] = None
            stages = []
            host_cpu_before = cpu_seconds()
            started = time.monotonic()
            if method == "baseline":
                tasks = [":" + module_path(name).replace("/", ":") + ":bootJar" for name in names]
                stages.append(run(gradle + tasks, source, results / (label + "-gradle.log")))
                def build_one(name):
                    image = registry + "/benchmark/baseline/" + name + ":current"
                    command = ["docker", "build", "-f", str(results / (name + ".Dockerfile")), "-t", image]
                    if scenario == "clean-output":
                        command.append("--no-cache")
                    return [run(command + ["."], source, results / (label + "-" + name + "-build.log")),
                            run(["docker", "push", image], source, results / (label + "-" + name + "-push.log"))]
                with ThreadPoolExecutor(max_workers=4) as pool:
                    for item in pool.map(build_one, names):
                        stages.extend(item)
            else:
                tasks = [":" + module_path(name).replace("/", ":") + ":jib" for name in names]
                stages.append(run(gradle + tasks + ["-Pstove.jib.baseImage=" + state["base"],
                    "-Pstove.jib.registry=" + registry + "/benchmark/jib", "-Pstove.jib.tag=current",
                    "-Pstove.jib.platform=arm64", "-Pstove.jib.insecure=true",
                    "-Djib.baseImageCache=" + str(root / "jib-base-cache"), "-Djib.console=plain"],
                    source, results / (label + "-gradle.log")))
            elapsed = time.monotonic() - started
            host_cpu = cpu_seconds() - host_cpu_before
            images = {}
            for name in names:
                repo = "benchmark/" + method + "/" + name
                after = image_record(registry, repo, "current")
                old = {layer["digest"] for layer in (before[name] or {}).get("layers", [])}
                after["changed_compressed_layer_bytes"] = sum(layer["size"] for layer in after["layers"] if layer["digest"] not in old)
                after["image"] = registry + "/" + repo + ":current"
                images[name] = after
            records.append({"label": label, "scenario": scenario, "repeat": repeat,
                            "seconds": elapsed, "host_busy_cpu_seconds": host_cpu, "stages": stages, "images": images,
                            "load_average_after": os.getloadavg()})
            save(report, records)
            print("DONE " + label + " " + str(round(elapsed, 3)) + "s", flush=True)
    marker = source / "apps/store/src/main/java/com/stove/store/config/ImageBuildBenchmarkMarker.java"
    if marker.exists():
        marker.unlink()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command")
    parser.add_argument("root")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    if args.command == "prepare":
        prepare(root)
    elif args.command in {"baseline", "jib"}:
        build(root, args.command)
    elif args.command == "status":
        for path in sorted((root / "results").glob("*-builds.json")):
            print(path.name)
            for record in json.loads(path.read_text()):
                print(record["label"], round(record["seconds"], 3))
        print(capture(["docker", "ps", "--format", "{{.Names}} {{.Status}} "]))
    elif args.command.startswith("runtime-") or args.command == "deploy-jib":
        from runtime import benchmark_runtime
        benchmark_runtime(root, "jib" if args.command == "deploy-jib" else args.command.replace("runtime-", "", 1),
                          keep=args.command == "deploy-jib")
    elif args.command == "cancel-runtime":
        for proc in Path("/proc").iterdir():
            if not proc.name.isdigit():
                continue
            try:
                command = (proc / "cmdline").read_bytes().decode().split("\0")
                if len(command) >= 4 and command[1].endswith("/worker.py") and (command[2].startswith("runtime-") or command[2] == "deploy-jib") and Path(command[3]).resolve() == root:
                    os.kill(int(proc.name), signal.SIGINT)
                    print("Cancelled benchmark runtime PID " + proc.name)
            except (OSError, UnicodeError):
                continue
    elif args.command == "cleanup":
        state = json.loads((root / "state.json").read_text())
        configurations = sorted((root / "results").glob("*-runtime-*/compose.json"))
        if configurations:
            subprocess.run(["docker", "compose", "-p", state["prefix"] + "-runtime", "-f", str(configurations[-1]),
                            "down", "--volumes", "--remove-orphans"], check=True)
        registry_name = state["prefix"] + "-registry"
        registry = json.loads(capture(["docker", "inspect", registry_name]))[0]
        expected = state["prefix"].replace("stove-imgbench-", "", 1)
        actual = registry["Config"]["Labels"].get("stove.image-benchmark", "").lower()
        if actual != expected:
            raise RuntimeError("Refusing to remove a registry outside this benchmark")
        subprocess.run(["docker", "rm", "-f", "-v", registry_name], check=True)
        print("Removed this benchmark's containers and disposable volumes; source and evidence remain.")
    elif args.command == "verify-deployment":
        state = json.loads((root / "state.json").read_text())
        deployment = json.loads((root / "results/deployment.json").read_text())
        docker = ["docker", "compose", "-p", deployment["project"], "-f", deployment["compose_file"]]
        verified = {}
        for name, port in MODULES.items():
            container = json.loads(capture(["docker", "inspect", capture(docker + ["ps", "-q", name])]))[0]
            repository = "benchmark/jib/" + name
            digest = deployment["images"][name]["digest"]
            manifest = request_json("http://" + state["registry"] + "/v2/" + repository + "/manifests/" + digest)
            assert container["Image"] == manifest["config"]["digest"], "Wrong image: " + name
            assert container["State"]["Running"] and container["State"]["Health"]["Status"] == "healthy", "Unhealthy: " + name
            assert container["RestartCount"] == 0, "Unexpected restarts: " + name
            assert container["Config"]["User"] == "100:101", "Wrong runtime UID/GID"
            bindings = [binding for items in container["NetworkSettings"]["Ports"].values() for binding in (items or [])]
            assert bindings and all(binding["HostIp"] == "127.0.0.1" for binding in bindings), "Non-loopback port"
            with urllib.request.urlopen("http://127.0.0.1:" + str(port + 40000) + "/actuator/health", timeout=5) as response:
                health = json.load(response)
            assert health["status"] == "UP", "HTTP health failed: " + name
            verified[name] = {"health": "healthy", "restarts": 0, "image_digest": digest,
                              "uid_gid": container["Config"]["User"], "loopback_only": True}
        save(root / "results/deployment-verification.json", {"success": True, "applications": verified})
        print("Verified 12 Jib image identities, healthy states, UID/GID, zero restarts, and loopback ports.")
    else:
        raise RuntimeError("Not implemented: " + args.command)


if __name__ == "__main__":
    main()
