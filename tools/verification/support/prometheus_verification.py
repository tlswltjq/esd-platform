"""Verify production scrape targets using an isolated real Prometheus server."""

import json
import math
import subprocess
import time
from urllib.parse import urlencode

import yaml


class PrometheusVerification:
    def __init__(self, root, output, services, ports, capture):
        self.root, self.output, self.capture = root, output, capture
        self.apps = set(ports) - {"mock-pg"}
        self.targets = {f"{name}:{ports[name]}" for name in self.apps}
        self.original_volumes = set(capture(["docker", "volume", "ls", "-q"]).splitlines())
        services["prometheus"] = {
            "image": "prom/prometheus:latest", "networks": ["app"],
            "command": ["--config.file=/etc/prometheus/prometheus.yml",
                        "--web.listen-address=127.0.0.1:9090", "--storage.tsdb.path=/prometheus"],
            "volumes": [str(root / "infra/prometheus/prometheus.yml") + ":/etc/prometheus/prometheus.yml:ro",
                        str(root / "infra/prometheus/alerts.yml") + ":/etc/prometheus/alerts.yml:ro"],
            "read_only": True, "tmpfs": ["/prometheus:rw,nosuid,nodev,size=128m,mode=1777"],
            "mem_limit": "256m", "cpus": 0.5,
        }

    def request(self, path):
        command = self.docker + ["exec", "-T", "prometheus", "wget", "-qO-", "-T", "5", "-Y", "off",
                                 "http://127.0.0.1:9090/api/v1/" + path]
        response = json.loads(self.capture(command, stderr=subprocess.DEVNULL))
        if response.get("status") != "success":
            raise RuntimeError("Prometheus API rejected " + path)
        return response["data"]

    def query(self, expression):
        return self.request("query?" + urlencode({"query": expression}))["result"]

    def verify(self, docker, run, result):
        self.docker = docker
        run(docker + ["up", "-d", "--no-build", "--pull", "never", "prometheus"], "prometheus-start")
        deadline = time.monotonic() + 120
        samples = []
        while time.monotonic() < deadline:
            try:
                samples = self.query('up{job="stove-services"}')
                targets = {sample["metric"]["instance"] for sample in samples}
                if targets == self.targets and len(samples) == len(self.targets) and all(
                        sample["value"][1] == "1" for sample in samples):
                    break
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                pass
            time.sleep(1)
        else:
            (self.output / "prometheus-last-up.json").write_text(json.dumps(samples, indent=2))
            raise RuntimeError("Prometheus did not collect every configured application within 120s")
        metrics = {}
        # Outbox metrics come from common:messaging, so only its users must export them.
        outbox = {name for name in self.apps
                  if "common:messaging" in (self.root / "apps" / name / "build.gradle").read_text()}
        required = {"jvm_memory_used_bytes": self.apps,
                    "stove_outbox_pending": outbox,
                    "stove_outbox_dead_total": outbox,
                    "stove_order_expirable": {"order"},
                    "stove_payment_refund_confirmation_delayed": {"payment"}}
        for name, applications in required.items():
            values = self.query(name + '{job="stove-services"}')
            observed = {sample["metric"].get("application") for sample in values}
            if observed != applications or not all(
                    math.isfinite(float(sample["value"][1])) and float(sample["value"][1]) >= 0
                    for sample in values):
                raise RuntimeError("Missing or invalid application metrics: " + name + " observed="
                                   + str(sorted(str(item) for item in observed)) + " expected=" + str(sorted(applications)))
            metrics[name] = values
        groups = self.request("rules")["groups"]
        loaded = {rule["name"] for group in groups for rule in group["rules"] if rule["type"] == "alerting"}
        configured = yaml.safe_load((self.root / "infra/prometheus/alerts.yml").read_text())
        expected = {rule["alert"] for group in configured["groups"] for rule in group["rules"] if "alert" in rule}
        if loaded != expected:
            raise RuntimeError("Loaded Prometheus alerts differ from production configuration")
        evidence = {"up": samples, "metrics": metrics, "loaded_alerts": sorted(loaded)}
        (self.output / "prometheus.json").write_text(json.dumps(evidence, indent=2))
        result["prometheus"] = {"targets_up": len(samples), "metrics": sorted(metrics), "alerts_loaded": len(loaded)}
        print("PASS: 11 real scrape targets, required runtime/business metrics and production alerts", flush=True)

    def check_preserved_volumes(self):
        remaining = set(self.capture(["docker", "volume", "ls", "-q"]).splitlines())
        if not self.original_volumes.issubset(remaining):
            raise RuntimeError("An original volume disappeared")
