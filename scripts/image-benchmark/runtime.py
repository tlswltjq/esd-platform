"""Deploy isolated image variants and measure readiness, fixed-rate HTTP, and cgroups."""

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import base64
import hashlib
import http.client
import http.cookiejar
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import statistics
import subprocess
import threading
import time
import urllib.request
import urllib.error
import urllib.parse

from worker import MODULES, capture, run, save


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def admin_token(auth):
    """Use the same Authorization Code + PKCE flow as the existing E2E client."""
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies), NoRedirect())
    def send(url, fields=None):
        data = urllib.parse.urlencode(fields).encode() if fields is not None else None
        request = urllib.request.Request(url, data=data)
        try:
            return opener.open(request, timeout=10)
        except urllib.error.HTTPError as response:
            return response
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    redirect = "http://localhost:3000/callback"
    authorize = auth + "/oauth2/authorize?" + urllib.parse.urlencode({"response_type": "code", "client_id": "studio-web",
        "scope": "openid profile studio commerce", "redirect_uri": redirect, "code_challenge": challenge, "code_challenge_method": "S256"})
    def resolve(location):
        return urllib.parse.urljoin(auth, location.replace("http://localhost:8091", auth))
    response = send(authorize)
    page = send(resolve(response.headers.get("Location", "/login"))).read().decode()
    csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page).group(1)
    response = send(auth + "/login", {"username": "admin-check@review.local", "password": "admin-password-1234", "_csrf": csrf})
    location = response.headers["Location"]
    for attempt in range(6):
        if location.startswith(redirect):
            break
        response = send(resolve(location))
        location = response.headers["Location"]
    code = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)["code"][0]
    response = send(auth + "/oauth2/token", {"grant_type": "authorization_code", "client_id": "studio-web",
        "code": code, "redirect_uri": redirect, "code_verifier": verifier})
    return json.load(response)["access_token"]


def free_port():
    with socket.socket() as connection:
        connection.bind(("127.0.0.1", 0))
        return connection.getsockname()[1]


def ready(url):
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status == 200
    except Exception:
        return False


def wait(check, description, timeout=240):
    started = time.monotonic()
    while not check():
        if time.monotonic() - started > timeout:
            raise RuntimeError("Timed out: " + description)
        time.sleep(0.5)
    print("READY " + description, flush=True)


class DockerConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(5)
        self.sock.connect("/var/run/docker.sock")


def docker_json(path):
    connection = DockerConnection("localhost")
    try:
        connection.request("GET", "/v1.49" + path)
        response = connection.getresponse()
        if response.status != 200:
            raise RuntimeError("Docker API status: " + str(response.status))
        return json.loads(response.read())
    finally:
        connection.close()


def snapshot(containers):
    samples = {}
    for name, container_id in containers.items():
        result = docker_json("/containers/" + container_id + "/stats?stream=false&one-shot=true")
        memory = result.get("memory_stats", {})
        stat = memory.get("stats", {})
        cached = stat.get("inactive_file", stat.get("total_inactive_file", 0))
        samples[name] = {"cpu_ns": result["cpu_stats"]["cpu_usage"]["total_usage"],
                         "memory_bytes": memory.get("usage", 0),
                         "working_set_bytes": max(0, memory.get("usage", 0) - cached),
                         "pids": result.get("pids_stats", {}).get("current")}
    return {"monotonic": time.monotonic(), "containers": samples, "host_load": os.getloadavg()}


def quantile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def load(base, paths, rate, seconds, containers, output):
    rows = []
    resource_samples = [snapshot(containers)]
    lock = threading.Lock()
    started = time.monotonic()
    def one(index, scheduled):
        launched = time.monotonic()
        path = paths[index % len(paths)]
        status, valid = 0, False
        try:
            with urllib.request.urlopen(base + path, timeout=10) as response:
                status = response.status
                body = json.load(response)
                data = body.get("data")
                valid = status == 200 and body.get("success") is True and data is not None
                if isinstance(data, dict) and "content" in data:
                    valid = valid and len(data["content"]) > 0
        except Exception:
            pass
        row = {"path": path, "status": status, "valid": valid,
               "latency_ms": (time.monotonic() - launched) * 1000,
               "schedule_delay_ms": max(0, (launched - scheduled) * 1000)}
        with lock:
            rows.append(row)
    stop = threading.Event()
    def sampler():
        while not stop.wait(2):
            resource_samples.append(snapshot(containers))
    thread = threading.Thread(target=sampler, daemon=True)
    thread.start()
    try:
        with ThreadPoolExecutor(max_workers=32) as pool:
            futures = []
            for index in range(rate * seconds):
                scheduled = started + index / rate
                delay = scheduled - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                futures.append(pool.submit(one, index, scheduled))
            for future in futures:
                future.result()
    finally:
        stop.set()
        thread.join(timeout=10)
    resource_samples.append(snapshot(containers))
    elapsed = time.monotonic() - started
    latencies = [row["latency_ms"] for row in rows]
    result = {"rate": rate, "duration_seconds": seconds, "elapsed_seconds": elapsed,
              "requests": len(rows), "failures": sum(not row["valid"] for row in rows),
              "schedule_delay_p95_ms": quantile([row["schedule_delay_ms"] for row in rows], .95),
              "latency_p50_ms": quantile(latencies, .5), "latency_p95_ms": quantile(latencies, .95),
              "latency_p99_ms": quantile(latencies, .99), "latency_mean_ms": statistics.mean(latencies),
              "completed_rps": len(rows) / elapsed, "per_path": {},
              "cpu_seconds": {}, "memory_mean_bytes": {}}
    for path in paths:
        values = [row["latency_ms"] for row in rows if row["path"] == path]
        result["per_path"][path] = {"p50_ms": quantile(values, .5), "p95_ms": quantile(values, .95)}
    for name in containers:
        result["cpu_seconds"][name] = (resource_samples[-1]["containers"][name]["cpu_ns"] -
                                         resource_samples[0]["containers"][name]["cpu_ns"]) / 1e9
        result["memory_mean_bytes"][name] = statistics.mean(
            sample["containers"][name]["working_set_bytes"] for sample in resource_samples)
    save(output.with_name(output.stem + "-requests.json"), rows)
    save(output.with_name(output.stem + "-resources.json"), resource_samples)
    save(output, result)
    if result["failures"] or len(rows) != rate * seconds or result["schedule_delay_p95_ms"] > 100:
        raise RuntimeError("Invalid load sample: " + str(output))
    return result


def benchmark_runtime(root, method, keep=False):
    state = json.loads((root / "state.json").read_text())
    results = root / "results"
    source = root / "baseline"  # Both variants run exactly the same E2E client code.
    prior = sorted(results.glob(method + "-runtime-*.json"))
    repeat = len(prior) + 1
    label = method + "-runtime-" + str(repeat)
    output = results / label
    if output.exists():
        output.rename(output.with_name(label + "-failed-" + str(int(time.time()))))
    output.mkdir(exist_ok=True)
    project = state["prefix"] + "-runtime"
    offset = 40000
    public = "http://127.0.0.1:" + str(8080 + offset)
    ports = {name: free_port() for name in ["mysql", "redis", "kafka", "minio"]}
    keys = {name: secrets.token_urlsafe(24) for name in ["catalog", "settlement", "payment", "pg-settlement", "control", "pg-db"]}
    env = os.environ.copy()
    env.update(AUTH_ISSUER=public, BFF_PUBLIC_AUTH_BASE=public, BFF_REDIRECT_URI=public + "/login/oauth2/code/gateway-bff",
        AUTH_REVIEWER_PASSWORD="reviewer-password-1234", AUTH_ADMIN_PASSWORD="admin-password-1234")
    config = json.loads(capture(["docker", "compose", "-f", "docker-compose.apps.yml", "config", "--format", "json"], source))
    builds = json.loads((results / (method + "-builds.json")).read_text())
    unchanged = next(record for record in builds if record["scenario"] == "no-change" and record["repeat"] == 3)
    for name, image in unchanged["images"].items():
        repository = "/v2/benchmark/" + method + "/" + name + "/manifests/"
        request = urllib.request.Request("http://" + state["registry"] + repository + image["digest"],
            headers={"Accept": "application/vnd.docker.distribution.manifest.v2+json"})
        with urllib.request.urlopen(request) as response:
            manifest = response.read()
            content_type = response.headers["Content-Type"]
        request = urllib.request.Request("http://" + state["registry"] + repository + "runtime",
            data=manifest, method="PUT", headers={"Content-Type": content_type})
        with urllib.request.urlopen(request):
            pass
    names = [name for name in MODULES if name not in {"gateway", "download", "mock-pg"}]
    init = output / "init.sql"
    init.write_text("\n".join("CREATE DATABASE stove_" + name + "; GRANT ALL PRIVILEGES ON stove_" + name + ".* TO 'stove'@'%';" for name in names))
    minio_image = "stove/minio:RELEASE.2025-04-22T22-12-26Z-local"
    if subprocess.run(["docker", "image", "inspect", minio_image], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        run(["docker", "build", "-t", minio_image, "infra/minio"], source, output / "minio-build.log")
    kafka_port = ports["kafka"]
    services = {
        "mysql": {"image": "mysql:8.0", "environment": {"MYSQL_ROOT_PASSWORD": "root1234", "MYSQL_USER": "stove", "MYSQL_PASSWORD": "stove1234"},
                  "volumes": [str(init) + ":/docker-entrypoint-initdb.d/01-schema.sql:ro"]},
        "pg-mysql": {"image": "mysql:8.0", "environment": {"MYSQL_ROOT_PASSWORD": keys["pg-db"], "MYSQL_DATABASE": "mock_pg", "MYSQL_USER": "mock_pg", "MYSQL_PASSWORD": keys["pg-db"]}},
        "redis": {"image": "redis:7-alpine"},
        "minio": {"image": minio_image, "ports": ["127.0.0.1:" + str(ports["minio"]) + ":9000"],
                  "environment": {"MINIO_ROOT_USER": "stove", "MINIO_ROOT_PASSWORD": "stove1234"}, "command": ["server", "/data"]},
        "kafka": {"image": "apache/kafka:3.9.0", "ports": ["127.0.0.1:" + str(kafka_port) + ":" + str(kafka_port)],
                  "environment": {"KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
                    "KAFKA_LISTENERS": "PLAINTEXT://:19092,CONTROLLER://:9093,EXTERNAL://:" + str(kafka_port),
                    "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://kafka:19092,EXTERNAL://localhost:" + str(kafka_port),
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT",
                    "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@kafka:9093", "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
                    "KAFKA_INTER_BROKER_LISTENER_NAME": "PLAINTEXT", "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
                    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1", "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
                    "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "true"}}}
    for name, port in MODULES.items():
        app_env = dict(config["services"][name]["environment"])
        app_env.update(TRACING_SAMPLE_RATE="0", JWT_ISSUER_URI=public, AUTH_ISSUER=public,
            BFF_PUBLIC_AUTH_BASE=public, BFF_REDIRECT_URI=public + "/login/oauth2/code/gateway-bff",
            AUTH_REVIEWER_EMAIL="reviewer-check@review.local", AUTH_REVIEWER_PASSWORD="reviewer-password-1234",
            AUTH_ADMIN_EMAIL="admin-check@review.local", AUTH_ADMIN_PASSWORD="admin-password-1234",
            CATALOG_DOWNLOAD_SERVICE_KEY=keys["catalog"], CATALOG_SETTLEMENT_SERVICE_KEY=keys["settlement"],
            MOCK_PG_PAYMENT_KEY=keys["payment"], MOCK_PG_SETTLEMENT_KEY=keys["pg-settlement"], MOCK_PG_CONTROL_KEY=keys["control"],
            MOCK_PG_DB_PASSWORD=keys["pg-db"], STORAGE_PRESIGN_ENDPOINT="http://127.0.0.1:" + str(ports["minio"]),
            STORAGE_BUCKET="stove-builds", STUDIO_ASSET_PUBLIC_BASE_URL=public,
            REVIEW_STORAGE_ACCESS_KEY="review-reader", REVIEW_STORAGE_SECRET_KEY="isolated-read-only-password",
            DOWNLOAD_STORAGE_ACCESS_KEY="download-reader", DOWNLOAD_STORAGE_SECRET_KEY="isolated-read-only-password")
        if name == "gateway":
            app_env["BFF_CLIENT_SECRET"] = "bff-local-only"
        services[name] = {"image": state["registry"] + "/benchmark/" + method + "/" + name + ":runtime",
            "environment": app_env, "mem_limit": "512m", "stop_grace_period": "40s",
            "ports": ["127.0.0.1:" + str(port + offset) + ":" + str(port)],
            "healthcheck": {"test": ["CMD-SHELL", "wget -qO- http://localhost:" + str(port) + "/actuator/health | grep -q UP || exit 1"],
                            "interval": "15s", "timeout": "3s", "start_period": "40s", "retries": 5}}
    compose = output / "compose.json"
    compose.write_text(json.dumps({"services": services}, indent=2))
    compose.chmod(0o600)
    docker = ["docker", "compose", "-p", project, "-f", str(compose)]
    infra = ["mysql", "pg-mysql", "redis", "kafka", "minio"]
    succeeded = False
    try:
        run(docker + ["up", "-d", *infra], source, output / "infra-up.log")
        wait(lambda: ready("http://127.0.0.1:" + str(ports["minio"]) + "/minio/health/ready"), "MinIO")
        def mysql_ready(name):
            password = "root1234" if name == "mysql" else keys["pg-db"]
            return subprocess.run(docker + ["exec", "-T", "-e", "MYSQL_PWD=" + password, name, "mysql", "-h127.0.0.1", "-uroot", "-e", "SELECT 1"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        for name in ["mysql", "pg-mysql"]:
            wait(lambda: mysql_ready(name), name)
        mc = docker + ["exec", "-T", "minio", "mc"]
        for arguments in [["alias", "set", "admin", "http://localhost:9000", "stove", "stove1234"], ["mb", "admin/stove-builds"]]:
            subprocess.run(mc + arguments, check=True, stdout=subprocess.DEVNULL)
        policy = json.dumps({"Version": "2012-10-17", "Statement": [
            {"Effect": "Allow", "Action": ["s3:GetObject"], "Resource": ["arn:aws:s3:::stove-builds/*"]},
            {"Effect": "Allow", "Action": ["s3:ListBucket"], "Resource": ["arn:aws:s3:::stove-builds"]}]})
        subprocess.run(docker + ["exec", "-T", "minio", "sh", "-c", "cat > /tmp/read-policy.json"], input=policy, text=True, check=True)
        subprocess.run(mc + ["admin", "policy", "create", "admin", "build-read", "/tmp/read-policy.json"], check=True, stdout=subprocess.DEVNULL)
        for user in ["review-reader", "download-reader"]:
            subprocess.run(mc + ["admin", "user", "add", "admin", user, "isolated-read-only-password"], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(mc + ["admin", "policy", "attach", "admin", "build-read", "--user", user], check=True, stdout=subprocess.DEVNULL)
        pull = run(docker + ["pull", *MODULES], source, output / "pull.log")
        started = time.monotonic()
        readiness = {}
        # Store's initial snapshot requires a ready Catalog. Limit simultaneous JVM
        # startup on four cores so a 3s DB connect timeout does not measure CPU starvation.
        groups = [["mock-pg", "auth", "gateway"], ["studio", "review", "catalog"],
                  ["store", "order", "payment"], ["license", "download", "settlement"]]
        for index, group in enumerate(groups):
            run(docker + ["up", "-d", *group], source, output / ("apps-up-" + str(index) + ".log"))
            pending = set(group)
            group_started = time.monotonic()
            while pending:
                for name in list(pending):
                    if ready("http://127.0.0.1:" + str(MODULES[name] + offset) + "/actuator/health"):
                        readiness[name] = time.monotonic() - started
                        pending.remove(name)
                        print("READY " + name + " " + str(round(readiness[name], 2)) + "s", flush=True)
                if time.monotonic() - group_started > 180:
                    raise RuntimeError("Apps not ready: " + str(pending))
                time.sleep(.25)
        startup_seconds = time.monotonic() - started
        containers = {name: capture(docker + ["ps", "-q", name]) for name in MODULES}
        def phase(name):
            return run(["./gradlew", ":e2e:settlementE2eTest", "--console=plain", "--no-daemon",
                "-Dstove.e2e.port-offset=" + str(offset), "-Dstove.e2e.settlement-phase=" + name,
                "-Dstove.e2e.settlement-evidence=" + str(output / "settlement-evidence.json")], source, output / (name + ".log"))
        for phase_name in ["initialPurchasesAndDownloads", "allocationAndOwnershipAreIsolated"]:
            phase(phase_name)
            print("PASS " + phase_name, flush=True)
        metrics_token = admin_token("http://127.0.0.1:" + str(MODULES["auth"] + offset))
        # Payment approval, License, actual ZIP download and Settlement allocation have now executed.
        subprocess.run(["./gradlew", "--stop"], cwd=source, stdout=subprocess.DEVNULL, check=True)
        evidence = json.loads((output / "settlement-evidence.json").read_text())
        code = evidence["products"][0]["productCode"]
        paths = ["/api/v1/storefront/products?size=10", "/api/v1/storefront/products?q=Settlement&size=10",
                 "/api/v1/storefront/products/" + code]
        for rate in [20, 60]:
            probe = load(public, paths, rate, 10, containers, output / ("probe-" + str(rate) + ".json"))
            print("PROBE " + str(rate) + " RPS p95=" + str(round(probe["latency_p95_ms"], 3)), flush=True)
        warmup = load(public, paths, 20, 20, containers, output / "warmup.json")
        measured = load(public, paths, 20, 60, containers, output / "load.json")
        prometheus = {}
        for name, port in MODULES.items():
            try:
                request = urllib.request.Request("http://127.0.0.1:" + str(port + offset) + "/actuator/prometheus",
                    headers={"Authorization": "Bearer " + metrics_token})
                with urllib.request.urlopen(request, timeout=5) as response:
                    text = response.read().decode()
                    if "jvm_memory_used_bytes" not in text:
                        raise RuntimeError("JVM metrics unavailable (possibly an authentication page)")
                    (output / (name + "-prometheus.txt")).write_text(text)
                    prometheus[name] = {"gc_pause_count": sum(float(line.split()[-1]) for line in text.splitlines()
                        if line.startswith("jvm_gc_pause_seconds_count{")),
                        "gc_pause_seconds": sum(float(line.split()[-1]) for line in text.splitlines()
                        if line.startswith("jvm_gc_pause_seconds_sum{"))}
            except Exception as error:
                prometheus[name] = {"error": str(error)}
        record = {"method": method, "repeat": repeat, "startup_all_seconds": startup_seconds,
                  "readiness_seconds": readiness, "cached_pull": pull, "load": measured,
                  "prometheus": prometheus, "memory_limit_bytes_per_app": 512 * 1024 * 1024,
                  "tracing_sample_rate": 0, "fixture_products": len(evidence["products"]),
                  "fixture_paid_orders": len(evidence["initialOrders"]), "load_paths": paths,
                  "service_http_loopback_ports": {name: port + offset for name, port in MODULES.items()},
                  "minio_loopback_port": ports["minio"],
                  "startup_groups": groups,
                  "images": {name: {"digest": image["digest"], "compressed_layer_bytes": image["compressed_layer_bytes"]}
                             for name, image in unchanged["images"].items()}, "left_running": keep}
        save(results / (label + ".json"), record)
        if keep:
            save(results / "deployment.json", {"project": project, "compose_file": str(compose),
                "gateway_loopback_port": 8080 + offset, "minio_loopback_port": ports["minio"],
                "application_count": len(MODULES), "variant": method,
                "images": record["images"]})
        succeeded = True
        print("RUNTIME DONE " + label + " p95=" + str(round(measured["latency_p95_ms"], 3)), flush=True)
    finally:
        for name in MODULES:
            result = subprocess.run(docker + ["logs", "--no-color", name], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (output / (name + ".log")).write_text(result.stdout)
        # Only this generated Compose project's resources are removed; shared data is untouched.
        if not (keep and succeeded):
            subprocess.run(docker + ["down", "--volumes", "--remove-orphans"], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
