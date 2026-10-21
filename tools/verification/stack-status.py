#!/usr/bin/env python3
"""Wait for the selected Compose services; never start or change containers."""

import argparse
import json
from pathlib import Path
import subprocess
import time


ROOT = Path(__file__).resolve().parents[2]


def capture(arguments):
    return subprocess.check_output(arguments, cwd=ROOT, text=True).strip()


def service_problem(service, containers, one_shot=False):
    if not containers:
        return service + ": missing"
    for container in containers:
        state = container["State"]
        if one_shot:
            if state["Status"] != "exited" or state["ExitCode"] != 0:
                return service + ": bootstrap has not exited successfully"
        elif not state.get("Running") or state.get("Paused") or state.get("Restarting"):
            return service + ": " + state["Status"]
        elif "Health" in state and state["Health"]["Status"] != "healthy":
            return service + ": " + state["Health"]["Status"]
    return None


def check_stack(command, services, one_shots):
    problems = []
    for service in services:
        ids = capture(command + ["ps", "--all", "--quiet", service]).splitlines()
        containers = json.loads(capture(["docker", "inspect", *ids])) if ids else []
        problem = service_problem(service, containers, service in one_shots)
        if problem:
            problems.append(problem)
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-f", "--file", action="append", help="Compose file (repeatable)")
    parser.add_argument("-p", "--project", help="Compose project; requires --file")
    parser.add_argument("--service", action="append", help="Service to check (default: all configured services)")
    parser.add_argument("--one-shot", action="append", default=[], help="Service expected to exit with code 0")
    parser.add_argument("--timeout", type=float, default=240)
    args = parser.parse_args()
    if args.timeout < 0:
        parser.error("--timeout must be nonnegative")
    if not args.file and (args.project or args.service or args.one_shot):
        parser.error("--project, --service and --one-shot require --file")
    if args.file:
        command = ["docker", "compose"]
        if args.project:
            command += ["-p", args.project]
        for filename in args.file:
            command += ["-f", str(Path(filename).resolve())]
        configured = capture(command + ["config", "--services"]).splitlines()
        services = args.service or configured
        if not services or set(services) - set(configured) or set(args.one_shot) - set(services):
            parser.error("selected services must exist in the Compose configuration")
        stacks = [(command, services, set(args.one_shot))]
    else:
        # Legacy optional infrastructure is not a dependency of the completed modules.
        stacks = [
            (["docker", "compose", "-p", "stove", "-f", "docker-compose.yml", "-f", "docker-compose.local.yml"],
             ["mysql", "mysql-bootstrap", "redis", "kafka", "minio"], {"mysql-bootstrap"}),
            (["docker", "compose", "-p", "stove-apps", "-f", "docker-compose.apps.yml", "-f", "docker-compose.apps.local.yml"],
             ["pg-mysql", "mock-pg", "auth", "gateway", "studio", "review", "catalog", "store", "order", "payment", "license", "download", "settlement"], set()),
        ]
    deadline = time.monotonic() + args.timeout
    while True:
        problems = []
        for command, services, one_shots in stacks:
            problems.extend(check_stack(command, services, one_shots))
        if not problems:
            print("PASS: selected Compose services are running/healthy; bootstrap jobs completed")
            return
        if time.monotonic() >= deadline:
            raise SystemExit("Stack not ready:\n" + "\n".join(problems))
        time.sleep(min(2, max(0, deadline - time.monotonic())))


if __name__ == "__main__":
    main()
