#!/usr/bin/env python3
"""Collect an OCI image benchmark without changing the shared deployment."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import subprocess


ROOT = Path(__file__).resolve().parents[2]
BASELINE_REVISION = "2ccad87cc39e7ec655fc58f12aec4cf36ac3a335"
JIB_REVISION = "bed7c6b25799de1995ce2c25a910b36a377a5584"
JIB_CONFIGURATION = ["build.gradle", "gradle/jib.gradle", "docker-compose.apps.jib.yml"]
INVENTORY = r'''
import json, os, platform, shutil, subprocess
def capture(args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return {"exit_code": result.returncode, "output": result.stdout.strip()}
print(json.dumps({
    "platform": platform.platform(), "architecture": platform.machine(),
    "cpu_count": os.cpu_count(), "uptime": capture(["uptime"]),
    "memory": capture(["free", "-b"]), "disk": capture(["df", "-B1", "."]),
    "java": capture(["java", "-version"]),
    "docker": capture(["docker", "version", "--format", "{{json .Server}}"]),
    "containers": capture(["docker", "ps", "--format", "{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"]),
    "processes": capture(["ps", "-eo", "pid,etime,pcpu,comm"]),
    "tools": {name: shutil.which(name) for name in ["python3", "docker", "curl", "rsync", "time", "k6"]},
}, indent=2))
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["inventory", "prepare", "baseline", "jib", "runtime-baseline", "runtime-jib", "deploy-jib", "verify-deployment", "cancel-runtime", "cleanup", "status", "collect", "overlay"])
    parser.add_argument("--host")
    parser.add_argument("--run")
    args = parser.parse_args()
    host = args.host or subprocess.check_output(
        ["git", "config", "--get", "stove.remote"], cwd=ROOT, text=True
    ).strip()
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host]
    if args.command == "inventory":
        subprocess.run(ssh + ["python3 -c " + shlex.quote(INVENTORY)], check=True)
        return
    runs = ROOT / "runs" / "image-benchmark"
    runs.mkdir(parents=True, exist_ok=True)
    state_file = runs / "latest.json"
    if args.command == "prepare":
        run_id = args.run or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        state = {"run_id": run_id, "remote": "stove/image-benchmark-" + run_id,
                 "revision": BASELINE_REVISION, "jib_revision": JIB_REVISION}
        state_file.write_text(json.dumps(state, indent=2))
    else:
        state = json.loads(state_file.read_text())
    remote = state["remote"]
    local = runs / state["run_id"]
    local.mkdir(exist_ok=True)
    print("Benchmark: " + state["run_id"], flush=True)
    def remote_run(arguments):
        subprocess.run(ssh + [shlex.join(arguments)], check=True)
    worker = Path(__file__).resolve().with_name("worker.py")
    if args.command == "prepare":
        archive = local / "baseline.tar"
        subprocess.run(["git", "archive", "--format=tar", "--output=" + str(archive), state["revision"]], cwd=ROOT, check=True)
        remote_run(["mkdir", "-p", remote + "/results"])
        subprocess.run(["rsync", "-az", str(archive), str(worker), host + ":" + remote + "/"], check=True)
        remote_run(["python3", remote + "/worker.py", "prepare", remote])
    elif args.command == "overlay":
        jib_revision = state.get("jib_revision", JIB_REVISION)
        historical = local / "historical-jib"
        for relative in JIB_CONFIGURATION:
            path = historical / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(subprocess.check_output(["git", "show", jib_revision + ":" + relative], cwd=ROOT))
            subprocess.run(["rsync", "-az", str(path), host + ":" + remote + "/jib/" + relative], check=True)
        subprocess.run(["rsync", "-az", str(worker), host + ":" + remote + "/worker.py"], check=True)
        metadata = {"revision": state["revision"], "jib_revision": jib_revision,
                    "baseline_archive_sha256": hashlib.sha256((local / "baseline.tar").read_bytes()).hexdigest(),
                    "jib_configuration_sha256": {relative: hashlib.sha256((historical / relative).read_bytes()).hexdigest()
                        for relative in JIB_CONFIGURATION}}
        metadata_file = local / "source.json"
        metadata_file.write_text(json.dumps(metadata, indent=2))
        subprocess.run(["rsync", "-az", str(metadata_file), host + ":" + remote + "/results/source.json"], check=True)
    elif args.command == "collect":
        subprocess.run(["rsync", "-az", host + ":" + remote + "/results/", str(local) + "/"], check=True)
        print("Results: " + str(local), flush=True)
    elif args.command == "status":
        remote_run(["python3", remote + "/worker.py", "status", remote])
    else:
        files = [str(worker)]
        runtime = worker.with_name("runtime.py")
        if runtime.exists():
            files.append(str(runtime))
        subprocess.run(["rsync", "-az", *files, host + ":" + remote + "/"], check=True)
        remote_run(["python3", remote + "/worker.py", args.command, remote])


if __name__ == "__main__":
    main()
