"""Tracing-specific configuration and fault phases for the existing isolated Jib runner."""

import json
import hashlib
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET


def free_port():
    with socket.socket() as connection:
        connection.bind(("127.0.0.1", 0))
        return connection.getsockname()[1]


class TracingVerification:
    def __init__(self, root, output, services, offset, run, capture, foundation_only=False):
        self.root, self.output, self.services, self.offset = root, output, services, offset
        self.run, self.capture = run, capture
        self.foundation_only = foundation_only
        self.tempo_port, self.grafana_port = free_port(), free_port()
        self.tempo_url = "http://127.0.0.1:" + str(self.tempo_port)
        self.grafana_url = "http://127.0.0.1:" + str(self.grafana_port)
        self.evidence = output / "tracing.json"
        self.phases = []
        self.original_volumes = set(capture(["docker", "volume", "ls", "-q"]).splitlines())
        temporary_volumes = set()
        container_ids = capture(["docker", "ps", "-aq"]).splitlines()
        if container_ids:
            for container in json.loads(capture(["docker", "inspect", *container_ids])):
                if any(key.startswith("org.testcontainers") for key in (container["Config"].get("Labels") or {})):
                    temporary_volumes.update(mount["Name"] for mount in container.get("Mounts", []) if mount["Type"] == "volume")
        self.original_volumes -= temporary_volumes
        (output / "volume-baseline.json").write_text(json.dumps({"preserve": sorted(self.original_volumes),
                                                               "managed_by_testcontainers": sorted(temporary_volumes)}, indent=2))
        self.revision = capture(["git", "rev-parse", "HEAD"])
        self.dirty = bool(capture(["git", "status", "--porcelain"]))
        sources = {}
        for name in capture(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"]).split("\0"):
            source = root / name
            if source.is_file() and (name.endswith((".java", ".gradle", ".py", ".yml", ".yaml")) or name == "gradle.properties" or "/src/main/resources/" in name):
                sources[name] = hashlib.sha256(source.read_bytes()).hexdigest()
        (output / "source-manifest.json").write_text(json.dumps({"revision": self.revision, "dirty": self.dirty, "sha256": sources}, indent=2))

        # Resolve a fixed version to its immutable digest before constructing the stack.
        images = {}
        for name, tag in [("tempo", "grafana/tempo:2.9.4"), ("grafana", "grafana/grafana:12.2.1")]:
            try:
                info = json.loads(capture(["docker", "image", "inspect", tag]))[0]
            except subprocess.CalledProcessError:
                run(["docker", "pull", tag], "pull-" + name)
                info = json.loads(capture(["docker", "image", "inspect", tag]))[0]
            images[name] = {"tag": tag, "id": info["Id"], "image": (info.get("RepoDigests") or [info["Id"]])[0]}
        self.images = images
        datasource = output / "grafana-datasources"
        datasource.mkdir(mode=0o755)
        (datasource / "tempo.yml").write_text("apiVersion: 1\ndatasources:\n  - name: Tempo\n    type: tempo\n    uid: tempo\n    access: proxy\n    url: http://tempo:3200\n    isDefault: true\n")
        services["tempo"] = {"image": images["tempo"]["image"], "networks": ["app"], "mem_limit": "512m",
                             "ports": ["127.0.0.1:" + str(self.tempo_port) + ":3200"],
                             "command": ["-config.file=/etc/tempo/tempo.yml"],
                             "volumes": [str(root / "infra/tempo/tempo.yml") + ":/etc/tempo/tempo.yml:ro", "tempo-data:/var/tempo"]}
        services["grafana"] = {"image": images["grafana"]["image"], "networks": ["app"], "mem_limit": "512m",
                               "ports": ["127.0.0.1:" + str(self.grafana_port) + ":3000"],
                               "environment": {"GF_AUTH_ANONYMOUS_ENABLED": "true", "GF_AUTH_ANONYMOUS_ORG_ROLE": "Viewer",
                                               "GF_ANALYTICS_REPORTING_ENABLED": "false", "GF_ANALYTICS_CHECK_FOR_UPDATES": "false"},
                               "volumes": [str(datasource) + ":/etc/grafana/provisioning/datasources:ro"]}
        services["payment"]["environment"]["PAYMENT_PROCESSING_ENABLED"] = str(foundation_only).lower()
        services["payment"]["environment"].update(PAYMENT_POLL_MS="1000", PAYMENT_RETRY_MS="5000", PAYMENT_BATCH_SIZE="20")
        if not foundation_only:
            services["payment"]["environment"]["SPRING_APPLICATION_JSON"] = json.dumps({
                "spring.kafka.producer.properties.max.block.ms": 5000,
                "spring.kafka.producer.properties.request.timeout.ms": 3000,
                "spring.kafka.producer.properties.delivery.timeout.ms": 8000})
            self.prepare_fault_fixture()

    def prepare_fault_fixture(self):
        fixture = self.output / "payment-fault-fixture"
        fixture.mkdir(mode=0o755)
        package = Path("com/stove/payment/verification")
        classes = fixture / "classes" / package
        classes.mkdir(parents=True)
        for source in (self.root / "apps/payment/build/classes/java/integrationTest" / package).glob("PaymentFaultConfiguration*.class"):
            shutil.copyfile(source, classes / source.name)
        if not (classes / "PaymentFaultConfiguration.class").exists():
            raise RuntimeError("Payment fault fixture was not compiled")
        libraries = fixture / "lib"
        libraries.mkdir()
        classpath = (self.root / "apps/payment/build/verification-classpath.txt").read_text()
        for entry in classpath.split(":"):
            source = Path(entry)
            if source.name.startswith("spring-boot-test-") and source.suffix == ".jar":
                shutil.copyfile(source, libraries / source.name)
        if not list(libraries.glob("*.jar")):
            raise RuntimeError("Payment fault fixture requires spring-boot-test")
        self.fixture = fixture

    def ready(self, url):
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                return response.status == 200
        except Exception:
            return False

    @staticmethod
    def wait_for(check, description, seconds=90):
        deadline = time.monotonic() + seconds
        while not check():
            if time.monotonic() >= deadline:
                raise RuntimeError("Timed out: " + description)
            time.sleep(1)

    def start(self, docker, compose):
        self.docker, self.compose = docker, compose
        self.run(docker + ["up", "-d", "--no-build", "tempo", "grafana"], "start-observability")
        self.wait_for(lambda: self.ready(self.tempo_url + "/ready"), "Tempo ready", 240)
        self.wait_for(lambda: self.ready(self.grafana_url + "/api/health"), "Grafana ready", 240)
        self.wait_for(lambda: self.ready(self.grafana_url + "/api/datasources/proxy/uid/tempo/ready"), "Grafana Tempo datasource", 90)

    def phase(self, method, scenario="initial", sampled=True, expected="PENDING"):
        label = scenario + "-" + method
        self.run(["./gradlew", ":e2e:tracingE2eTest", "--offline", "--no-daemon", "--console=plain",
                  "-Dstove.e2e.port-offset=" + str(self.offset), "-Dstove.e2e.tempo-base-url=" + self.tempo_url,
                  "-Dstove.e2e.tracing-evidence=" + str(self.evidence), "-Dstove.e2e.tracing-phase=" + method,
                  "-Dstove.e2e.tracing-scenario=" + scenario, "-Dstove.e2e.tracing-sampled=" + str(sampled).lower(),
                  "-Dstove.e2e.tracing-expected-state=" + expected], label)
        target = self.output / ("junit-" + label)
        shutil.copytree(self.root / "e2e/build/test-results/tracingE2eTest", target)
        suites = [ET.parse(path).getroot() for path in target.glob("TEST-*.xml")]
        if sum(int(s.get("tests", 0)) for s in suites) != 1 or any(int(s.get(k, 0)) for s in suites for k in ["failures", "errors", "skipped"]):
            raise RuntimeError("Tracing requires one executed check without skips: " + label)
        self.phases.append(label)
        print("PASS: " + label, flush=True)

    def restart_payment(self, worker=True, fault=None, tracing_enabled=True):
        self.run(self.docker + ["logs", "--no-color", "payment"], "payment-before-restart-" + str(len(self.phases)))
        configuration = json.loads(self.compose.read_text())
        payment = configuration["services"]["payment"]
        payment["environment"]["PAYMENT_PROCESSING_ENABLED"] = str(worker).lower()
        payment["environment"]["MANAGEMENT_TRACING_ENABLED"] = str(tracing_enabled).lower()
        for key in ["entrypoint", "volumes", "command"]:
            payment.pop(key, None)
        if fault:
            payment["volumes"] = [str(self.fixture) + ":/verification:ro"]
            payment["entrypoint"] = ["sh", "-c", "exec java $$JAVA_OPTS -cp '/app/resources:/app/classes:/app/libs/*:/verification/classes:/verification/lib/*' com.stove.payment.PaymentServiceApplication \"$$@\"", "payment-verification"]
            payment["command"] = ["--spring.main.sources=com.stove.payment.verification.PaymentFaultConfiguration", "--stove.verification." + fault + "=true"]
        self.compose.write_text(json.dumps(configuration, indent=2))
        self.run(self.docker + ["up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "180", "payment"],
                 "restart-payment-" + str(len(self.phases)) + "-" + (fault or "normal"))

    def sql(self, statement):
        return self.capture(self.docker + ["exec", "-T", "mysql", "mysql", "-ustove", "-pstove1234", "-N", "-e", statement], stderr=subprocess.DEVNULL)

    def active(self, scenario):
        purchases = json.loads(self.evidence.read_text())["purchases"]
        return [p for p in purchases if p["scenario"].startswith(scenario)]

    def payment_count(self, scenario, condition):
        ids = ",".join("'" + p["paymentId"] + "'" for p in self.active(scenario))
        return self.sql("SELECT COUNT(*) FROM stove_payment.purchase_payment WHERE payment_id IN (" + ids + ") AND " + condition)

    def ledger_check(self):
        purchases = json.loads(self.evidence.read_text())["purchases"]
        count = len(purchases)
        checks = {
            "payment": self.sql("SELECT COUNT(*) FROM stove_payment.purchase_payment WHERE status='PAID'"),
            "payment_outbox": self.sql("SELECT COUNT(*) FROM stove_payment.outbox_event WHERE event_type='OrderPaymentCompleted' AND status='SENT'"),
            "order_inbox": self.sql("SELECT COUNT(*) FROM stove_order.processed_event"),
            "license": self.sql("SELECT COUNT(*) FROM stove_license.product_license"),
            "pg": self.capture(self.docker + ["exec", "-T", "pg-mysql", "mysql", "-umock_pg", "-p" + self.pg_password,
                                              "-N", "-e", "SELECT COUNT(*) FROM mock_pg.pg_charge"], stderr=subprocess.DEVNULL)}
        if not self.foundation_only:
            checks["trace_metadata"] = self.sql("SELECT COUNT(*) FROM stove_payment.payment_request_trace")
        if any(value != str(count) for value in checks.values()):
            raise RuntimeError("Purchase/PG/Outbox/license counts disagree: " + str(checks))
        return checks

    def set_sampling(self, probability):
        configuration = json.loads(self.compose.read_text())
        names = [name for name in configuration["services"] if name in self.app_names and name != "mock-pg"]
        for name in names:
            configuration["services"][name]["environment"]["TRACING_SAMPLE_RATE"] = str(probability)
        self.compose.write_text(json.dumps(configuration, indent=2))
        # Keep the same bounded startup order as scripts/start-apps.sh. Restarting
        # all JVMs together can exhaust DB handshake timeouts on a local machine.
        for index, group in enumerate([["auth", "gateway"], ["studio", "review", "catalog"],
                                       ["store", "order", "payment"], ["license", "download", "settlement"]]):
            self.run(self.docker + ["up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "180", *group],
                     "sampling-" + str(probability) + "-" + str(index))

    def verify(self, result, app_names, pg_password):
        self.app_names, self.pg_password = app_names, pg_password
        result["tracing"] = {"images": self.images, "tempo_url": self.tempo_url, "grafana_url": self.grafana_url,
                             "sampling": 1, "phases": self.phases, "trace_policy": "saved-parent-child-span",
                             "revision": self.revision, "dirty": self.dirty,
                             "limits_seconds": {"infrastructure": 240, "apps_per_restart_group": 180, "tempo_trace": 90},
                             "tempo_retention": "1h"}
        settings = {}
        for name in app_names:
            if name == "mock-pg":
                continue
            container = self.capture(self.docker + ["ps", "-q", name])
            info = json.loads(self.capture(["docker", "inspect", container]))[0]
            environment = dict(entry.split("=", 1) for entry in info["Config"]["Env"])
            selected = {key: environment[key] for key in ["TRACING_SAMPLE_RATE", "OTLP_ENDPOINT", "PAYMENT_POLL_MS", "PAYMENT_RETRY_MS", "PAYMENT_BATCH_SIZE"] if key in environment}
            if selected.get("TRACING_SAMPLE_RATE") != "1" or selected.get("OTLP_ENDPOINT") != "http://tempo:4318/v1/traces":
                raise RuntimeError("Unexpected tracing runtime environment: " + name)
            settings[name] = selected
        result["tracing"]["runtime_settings"] = settings
        if self.foundation_only:
            result["tracing"]["trace_policy"] = "common-http-foundation"
            result["tracing"]["payment_migration"] = self.sql("SELECT version FROM stove_payment.flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")
            self.verify_foundation()
            result["tracing"]["ledger_counts"] = self.ledger_check()
            self.grafana_trace(result, self.active("recovered")[0]["traceId"])
            return
        self.phase("prepareTwoDurableReceipts")
        if self.payment_count("initial", "status='PENDING'") != "2":
            raise RuntimeError("Initial receipts were not durably pending before restart")
        self.restart_payment()
        self.phase("completedPurchasesKeepTheirOriginalTraceAndDownloadSeparately")

        for scenario, fault, error in [("pg-response-loss", "fail-after-provider", "PG_UNAVAILABLE"),
                                       ("result-rollback", "fail-after-outbox", "RESULT_UNCONFIRMED")]:
            self.restart_payment(fault=fault)
            self.phase("preparePurchase", scenario)
            self.wait_for(lambda: self.payment_count(scenario, "status='PENDING' AND last_error_code='" + error + "'") == "1", scenario)
            self.restart_payment()
            self.phase("completedPurchasesKeepTheirOriginalTraceAndDownloadSeparately", scenario)

        self.restart_payment(worker=False)
        self.phase("preparePurchase", "broker-retry")
        # Pausing keeps Docker DNS available to a freshly started client while
        # the broker cannot respond, so this tests publishing failure itself.
        self.run(self.docker + ["pause", "kafka"], "pause-broker")
        self.restart_payment()
        self.wait_for(lambda: self.payment_count("broker-retry", "status='PAID'") == "1", "Payment completes during broker outage")
        number = self.active("broker-retry")[0]["orderNo"]
        pending = "SELECT trace_parent FROM stove_payment.outbox_event WHERE aggregate_id='" + number + "' AND status='PENDING'"
        saved_parent = self.sql(pending)
        if not saved_parent.startswith("00-" + self.active("broker-retry")[0]["traceId"] + "-"):
            raise RuntimeError("Pending Outbox lost its original payment trace")
        self.wait_for(lambda: "not present in metadata after" in self.capture(self.docker + ["logs", "--no-color", "payment"], stderr=subprocess.STDOUT)
                      or "outbox 발행 실패" in self.capture(self.docker + ["logs", "--no-color", "payment"], stderr=subprocess.STDOUT),
                      "Actual Outbox send failure", 90)
        self.run(self.docker + ["logs", "--no-color", "payment"], "broker-send-failure")
        if self.sql(pending) != saved_parent:
            raise RuntimeError("Outbox parent changed after a failed send")
        result["tracing"]["broker_send_failure_observed"] = True
        self.run(self.docker + ["unpause", "kafka"], "unpause-broker")
        self.phase("completedPurchasesKeepTheirOriginalTraceAndDownloadSeparately", "broker-retry")

        self.run(self.docker + ["stop", "tempo"], "stop-tempo")
        self.phase("preparePurchase", "tempo-outage", expected="PAID")
        self.phase("businessCompletesWithoutTraceCollection", "tempo-outage")
        self.run(self.docker + ["start", "tempo"], "start-tempo")
        self.wait_for(lambda: self.ready(self.tempo_url + "/ready"), "Tempo recovery")

        self.restart_payment(tracing_enabled=False)
        self.phase("preparePurchase", "disabled-payment-tracing", expected="PAID")
        self.phase("businessCompletesWithoutTraceCollection", "disabled-payment-tracing")
        self.restart_payment()
        self.set_sampling(0)
        self.phase("preparePurchase", "sampling-zero", sampled=False, expected="PAID")
        self.phase("businessCompletesWithoutTraceCollection", "sampling-zero")
        self.set_sampling(1)
        self.phase("preparePurchase", "recovered", expected="PAID")
        self.phase("completedPurchasesKeepTheirOriginalTraceAndDownloadSeparately", "recovered")
        result["tracing"]["ledger_counts"] = self.ledger_check()
        (self.output / "payment-ledger.tsv").write_text(self.sql(
            "SELECT p.payment_id,p.order_no,p.request_event_id,p.result_event_id,p.status,t.trace_parent "
            "FROM stove_payment.purchase_payment p LEFT JOIN stove_payment.payment_request_trace t USING(payment_id) ORDER BY p.id"))
        (self.output / "payment-outbox.tsv").write_text(self.sql(
            "SELECT event_id,aggregate_id,event_type,status,retry_count,trace_parent FROM stove_payment.outbox_event ORDER BY id"))
        state = json.loads(self.evidence.read_text())
        for purchase in state["purchases"]:
            if not purchase.get("businessVerified"):
                raise RuntimeError("Missing business assertion: " + purchase["scenario"])
            if purchase["scenario"] not in {"tempo-outage", "disabled-payment-tracing", "sampling-zero"} and not purchase.get("traceVerified"):
                raise RuntimeError("Missing trace assertion: " + purchase["scenario"])
        self.grafana_trace(result, self.active("recovered")[0]["traceId"])

    def verify_foundation(self):
        self.phase("prepareTwoDurableReceipts", expected="PAID")
        self.phase("verifyCommonHttpTracing")
        self.run(self.docker + ["stop", "tempo"], "stop-tempo")
        self.phase("preparePurchase", "tempo-outage", expected="PAID")
        self.phase("businessCompletesWithoutTraceCollection", "tempo-outage")
        self.run(self.docker + ["start", "tempo"], "start-tempo")
        self.wait_for(lambda: self.ready(self.tempo_url + "/ready"), "Tempo recovery")
        self.set_sampling(0)
        self.phase("preparePurchase", "sampling-zero", sampled=False, expected="PAID")
        self.phase("businessCompletesWithoutTraceCollection", "sampling-zero")
        self.set_sampling(1)
        self.phase("preparePurchase", "recovered", expected="PAID")
        self.phase("verifyCommonHttpTracing", "recovered")

    def grafana_trace(self, result, trace_id):
        request = urllib.request.Request(self.grafana_url + "/api/datasources/proxy/uid/tempo/api/traces/" + trace_id,
                                         headers={"Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            trace = json.load(response)
            if not (trace.get("batches") or trace.get("resourceSpans")):
                raise RuntimeError("Grafana datasource returned no spans")
            (self.output / "grafana-trace.json").write_text(json.dumps(trace, indent=2))
        result["tracing"]["grafana_trace_id"] = trace_id

    def check_preserved_volumes(self):
        remaining = set(self.capture(["docker", "volume", "ls", "-q"]).splitlines())
        missing = self.original_volumes - remaining
        if missing:
            raise RuntimeError("Original volumes disappeared: " + str(sorted(missing)))
