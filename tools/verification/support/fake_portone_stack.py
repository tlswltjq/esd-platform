"""Isolated 10-app stack (11 services with Download stubbed) wired to the local PortOne fake.

Shared by the fake-PortOne journeys (decisions 111, 119). Each run owns a Compose project,
its own MySQL/Redis/Kafka/MinIO ports, a fixed application port offset (decision 105) and an
evidence directory under runs/. Only its own containers/volumes are removed; protected
containers and named volumes are compared before/after and must be retained.
"""
from pathlib import Path
import base64, json, os, re, secrets, socket, subprocess, tempfile, threading, time, traceback, urllib.error, urllib.parse, urllib.request, uuid

from support.bff import BffClient
from support.boot_jars import application_jar
from support.minio_image import ensure_minio_image
from support import ports as port_rules
from support.portone_fake import PortOneFake

APPS = {"auth": 8091, "studio": 8085, "gateway": 8080, "review": 8086, "catalog": 8081, "store": 8087,
        "order": 8082, "payment": 8083, "license": 8084, "settlement": 8089}
BUYER_PASSWORD = "creator-password-1234"
# Short, fixed OTel export latency for tracing journeys only (support/refund_tracing.export_bound()).
TRACE_EXPORT_ENV = dict(MANAGEMENT_TRACING_OPENTELEMETRY_EXPORT_SCHEDULE_DELAY="1s", MANAGEMENT_TRACING_OPENTELEMETRY_EXPORT_TIMEOUT="5s",
                        MANAGEMENT_OTLP_TRACING_TIMEOUT="5s", MANAGEMENT_OTLP_TRACING_CONNECT_TIMEOUT="2s")
ADMIN_EMAIL, ADMIN_PASSWORD = "admin-check@review.local", "admin-password-1234"


# Reject every non-loopback urllib target, including redirects and inherited proxies.
class LocalOnly(urllib.request.BaseHandler):
    def default_open(self, request):
        parsed = urllib.parse.urlsplit(request.full_url)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
            raise RuntimeError("External HTTP target rejected")


urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({}), LocalOnly()))


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class FakePortOneStack:
    def __init__(self, repo, tool, offset_env, prefix, tracing=False):
        self.repo = Path(repo)
        # Tracing journeys add an isolated Tempo and sample every request; other journeys keep sampling at 0.
        self.tracing = tracing
        self.offset = port_rules.application_offset(tool, offset_env, APPS.values())
        out_root = self.repo / "runs"
        out_root.mkdir(exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix=prefix, dir=out_root))
        self.project = self.directory.name
        self.events = self.directory / "events.log"
        self.portone = {key: secrets.token_urlsafe(24) for key in ["PORTONE_STORE_ID", "PORTONE_CHANNEL_KEY", "PORTONE_API_SECRET"]}
        self.portone.update(PORTONE_WEBHOOK_SECRET="whsec_" + base64.b64encode(secrets.token_bytes(32)).decode(),
                            PORTONE_WEBHOOK_URL="https://webhook.invalid/api/v1/payments/webhooks/portone")
        self.settlement_secret = secrets.token_urlsafe(24)
        self.sdk_secret = secrets.token_urlsafe(24)
        # The PortOne console is a separate principal: only the journey tool holds it, never an app.
        self.console_secret = secrets.token_urlsafe(24)
        self.gradle_network = [] if os.environ.get("GITHUB_ACTIONS") == "true" else ["--offline"]
        self.fake = PortOneFake({"payment": self.portone["PORTONE_API_SECRET"], "settlement": self.settlement_secret,
                                 "sdk": self.sdk_secret, "console": self.console_secret},
                                self.portone["PORTONE_STORE_ID"], self.portone["PORTONE_CHANNEL_KEY"])
        self.infra = {name: free_port() for name in ["mysql", "redis", "kafka", "minio"] + (["tempo", "otlp"] if tracing else [])}
        self.tempo_url = "http://127.0.0.1:" + str(self.infra["tempo"]) if tracing else None
        self.minio_image = ensure_minio_image(self.repo)
        for image in ["mysql:8.0", "redis:7-alpine", "apache/kafka:3.9.0"] + (["grafana/tempo:2.9.4"] if tracing else []):
            subprocess.run(["docker", "image", "inspect", image], check=True, stdout=subprocess.DEVNULL, timeout=30)
        self.log("Evidence: " + str(self.directory))
        self.compose = self.write_compose()
        self.docker = ["docker", "compose", "-p", self.project, "-f", str(self.compose)]
        self.processes = []
        self.state = {"step": "starting", "stepText": "스택을 띄우고 있습니다.", "payable": False, "orderNo": None, "orders": {}, "checks": {}}
        self.lock = threading.Lock()
        self.buyer = None
        self.info = {}
        self.public = f"http://127.0.0.1:{8080 + self.offset}"

    # --- evidence ------------------------------------------------------------------------
    def log(self, message):
        line = time.strftime("%H:%M:%S") + " " + message
        print(line, flush=True)
        with self.events.open("a") as handle:
            handle.write(line + "\n")

    def save_state(self):
        (self.directory / "state.json").write_text(json.dumps(self.state, indent=2, ensure_ascii=False))

    def set_step(self, step, text, payable=False, order_no=None):
        with self.lock:
            self.state.update(step=step, stepText=text, payable=payable)
            if order_no is not None:
                self.state["orderNo"] = order_no
            self.save_state()
        self.log("STEP " + step + ": " + text)

    def record(self, name, value):
        """A journey assertion that passed; counted as one step in summary.json."""
        with self.lock:
            self.state["checks"][name] = str(value)
            self.save_state()
        self.log("PASS: " + name + " = " + str(value))

    # --- waiting and reading ---------------------------------------------------------------
    @staticmethod
    def ready(url):
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                return response.status == 200
        except Exception:
            return False

    def wait_for(self, check, description, seconds=180, interval=1):
        deadline = time.monotonic() + seconds
        while True:
            try:
                if check():
                    break
            except AssertionError:
                raise
            except Exception:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Timed out: " + description)
            time.sleep(interval)
        self.log("Ready: " + description)

    def sql(self, query):
        return subprocess.check_output(self.docker + ["exec", "-T", "mysql", "mysql", "-ustove", "-pstove1234", "-N", "-e", query],
                                       text=True, stderr=subprocess.DEVNULL, timeout=30).strip()

    def check(self, name, query, expected, seconds=120):
        self.wait_for(lambda: self.sql(query) == str(expected), name + " = " + str(expected), seconds, 2)
        with self.lock:
            self.state["checks"][name] = str(expected)
            self.save_state()

    def metric(self, app, name, labels):
        """Value of one Prometheus sample from an app's loopback actuator, or 0 when the series is absent."""
        with urllib.request.urlopen(f"http://127.0.0.1:{APPS[app] + self.offset}/actuator/prometheus", timeout=5) as response:
            text = response.read().decode()
        for line in text.splitlines():
            if line.startswith(name + "{") and all(f'{k}="{v}"' in line for k, v in labels.items()):
                return float(line.rsplit(" ", 1)[1])
        return 0.0

    # --- PortOne fake helpers --------------------------------------------------------------
    def fake_post(self, path, body, secret):
        request = urllib.request.Request(self.fake.base_url + path, data=json.dumps(body).encode(),
                                         headers={"Authorization": "PortOne " + secret, "Content-Type": "application/json"})
        try:
            response = urllib.request.urlopen(request, timeout=5)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            return response.status, json.load(response)

    def open_checkout(self, order_no):
        """Buyer reads the server checkout and the payment window authenticates one new attempt."""
        checkout = self.buyer.data("/api/v1/payments/" + order_no + "/checkout")
        status, sdk = self.fake_post("/_fake/sdk/request-payment", checkout, self.sdk_secret)
        assert status == 200, "fake SDK HTTP " + str(status) + " " + json.dumps(sdk)
        return checkout, sdk

    def console(self, payment_id, action, body=None):
        status, value = self.fake_post("/_fake/control/payments/" + payment_id + "/" + action, body or {}, self.console_secret)
        assert status == 200, "fake console " + action + " HTTP " + str(status) + " " + json.dumps(value)
        return value

    def submit_approval(self, order_no, sdk):
        status, value = self.buyer.call("POST", "/api/v1/payments/" + order_no + "/approvals", dict(requestId=str(uuid.uuid4()), **sdk))
        assert status == 202, "approval HTTP " + str(status) + " " + json.dumps(value, ensure_ascii=False)
        return value["data"]

    def requests_for(self, payment_id, suffix, method="POST"):
        return [r for r in self.fake.requests if r["method"] == method and r["path"] == "/payments/" + payment_id + suffix]

    def confirm_statuses(self, payment_id):
        return [r["status"] for r in self.requests_for(payment_id, "/confirm")]

    def lookups(self, payment_id):
        """PortOne GET /payments/{id} calls by Payment (current-attempt lookups, history excluded)."""
        return len([r for r in self.fake.requests if r["method"] == "GET" and r["caller"] == "payment"
                    and r["path"].split("?")[0] == "/payments/" + payment_id])

    def statuses(self, order_no, approval_id=None):
        """(Order, Payment[, approval]) status as the buyer sees them."""
        values = (self.buyer.data("/api/v1/orders/" + order_no)["status"], self.buyer.data("/api/v1/payments/" + order_no)["status"])
        if approval_id is not None:
            values += (self.buyer.data("/api/v1/payments/" + order_no + "/approvals/" + approval_id)["status"],)
        return values

    def admit_pay_pending(self, order_no):
        """Admitted approval whose confirm answers PAY_PENDING: Order PAYMENT_PROCESSING, approval ADMITTED, one confirm."""
        self.wait_payment_pending(order_no)
        checkout, sdk = self.open_checkout(order_no)
        payment_id = checkout["paymentId"]
        self.console(payment_id, "arm-confirm", {"outcome": "PAY_PENDING"})
        approval_id = self.submit_approval(order_no, sdk)["requestId"]
        self.wait_for(lambda: self.confirm_statuses(payment_id) == [200]
                      and self.statuses(order_no, approval_id) == ("PAYMENT_PROCESSING", "PENDING", "ADMITTED"),
                      "admitted PAY_PENDING " + order_no, 120, 2)
        assert self.fake.view_history(payment_id)[-1]["status"] == "PAY_PENDING"
        return payment_id, sdk, approval_id

    def storage(self):
        return self.buyer.data("/api/v1/storage?page=0&size=50")["content"]

    # --- shared journey steps ----------------------------------------------------------------
    def create_order(self, name):
        product = self.info["product"]
        request = {"requestId": str(uuid.uuid4()), "productCode": product["productCode"],
                   "expectedContentVersion": product["contentVersion"], "expectedAmount": product["price"]}
        status, value = self.buyer.call("POST", "/api/v1/orders", request)
        assert status == 200, "order create HTTP " + str(status)
        order_no = value["data"]["orderNo"]
        with self.lock:
            self.state["orders"][order_no] = {"name": name, "createdAt": time.time()}
            self.save_state()
        self.log("Order " + name + " created: " + order_no)
        return order_no

    def wait_payment_pending(self, order_no):
        self.wait_for(lambda: self.buyer.data("/api/v1/payments/" + order_no)["status"] == "PENDING", "payment PENDING " + order_no, 120)

    def pay(self, name, order_no, label):
        self.wait_payment_pending(order_no)
        self.set_step(name, label, order_no=order_no)
        _, sdk = self.open_checkout(order_no)
        self.submit_approval(order_no, sdk)
        self.wait_for(lambda: self.buyer.data("/api/v1/orders/" + order_no)["status"] == "PAID", "order PAID " + order_no, 180)
        return order_no

    def register(self, order_no):
        def registered():
            storage = self.buyer.data("/api/v1/storage?page=0&size=50")
            item = next((i for i in storage["content"] if i["orderNo"] == order_no), None)
            if item is None:
                return False
            status, value = self.buyer.call("POST", "/api/v1/storage/" + item["licenseId"] + "/registration")
            assert status == 200, "registration HTTP " + str(status) + " " + json.dumps(value, ensure_ascii=False)
            return True
        self.wait_for(registered, "registered " + order_no, 120, 2)
        library = self.buyer.data("/api/v1/library/" + self.info["product"]["productCode"])
        assert library["status"] == "ACTIVE"
        with self.lock:
            self.state["orders"][order_no]["licenseId"] = library["licenseId"]
            self.save_state()
        return library["licenseId"]

    def admin(self):
        client = BffClient(self.public)
        client.login(ADMIN_EMAIL, ADMIN_PASSWORD, role="ADMIN")
        return client

    # --- resources ---------------------------------------------------------------------------
    def write_compose(self):
        init = self.directory / "init.sql"
        dbs = ["auth", "studio", "review", "catalog", "store", "order", "payment", "license", "settlement"]
        init.write_text(" ".join("CREATE DATABASE stove_" + d + ";" for d in dbs) + " " +
                        " ".join("GRANT ALL PRIVILEGES ON stove_" + d + ".* TO 'stove'@'%';" for d in dbs) + "\n")
        compose = self.directory / "compose.yml"
        infra = self.infra
        compose.write_text(f"""services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root1234
      MYSQL_USER: stove
      MYSQL_PASSWORD: stove1234
    ports: ["127.0.0.1:{infra['mysql']}:3306"]
    volumes: ["{init}:/docker-entrypoint-initdb.d/01-init.sql:ro"]
  redis:
    image: redis:7-alpine
    ports: ["127.0.0.1:{infra['redis']}:6379"]
  kafka:
    image: apache/kafka:3.9.0
    ports: ["127.0.0.1:{infra['kafka']}:{infra['kafka']}"]
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:19092,CONTROLLER://:9093,EXTERNAL://:{infra['kafka']}
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:19092,EXTERNAL://localhost:{infra['kafka']}
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
  minio:
    image: {self.minio_image}
    ports: ["127.0.0.1:{infra['minio']}:9000"]
    environment:
      MINIO_ROOT_USER: stove
      MINIO_ROOT_PASSWORD: stove1234
    command: ["server", "/data"]
""" + (f"""  tempo:
    image: grafana/tempo:2.9.4
    command: ["-config.file=/etc/tempo/tempo.yml"]
    ports: ["127.0.0.1:{infra['tempo']}:3200", "127.0.0.1:{infra['otlp']}:4318"]
    volumes: ["{self.repo / 'infra/tempo/tempo.yml'}:/etc/tempo/tempo.yml:ro", "tempo-data:/var/tempo"]
volumes:
  tempo-data: {{}}
""" if self.tracing else ""))
        compose.chmod(0o600)
        return compose

    def container_snapshot(self, project_filter=None):
        command = ["docker", "ps", "-a", "--format", "{{.ID}}"]
        if project_filter:
            command += ["--filter", "label=com.docker.compose.project=" + project_filter]
        ids = subprocess.check_output(command, text=True, timeout=30).split()
        containers = {}
        for container_id in ids:
            inspected = subprocess.run(["docker", "inspect", container_id], text=True, stdout=subprocess.PIPE,
                                       stderr=subprocess.DEVNULL, timeout=30)
            # Ephemeral containers may disappear between ps and inspect.
            if inspected.returncode:
                continue
            row = json.loads(inspected.stdout)[0]
            containers[row["Id"]] = dict(name=row["Name"].lstrip("/"),
                testcontainers=(row.get("Config", {}).get("Labels") or {}).get("org.testcontainers") == "true",
                volumes=sorted(m["Name"] for m in row.get("Mounts", []) if m.get("Type") == "volume"))
        return containers

    def snapshot(self, label):
        result = dict(containers=self.container_snapshot(),
                      volumes=sorted(subprocess.check_output(["docker", "volume", "ls", "-q"], text=True, timeout=30).split()))
        (self.directory / ("resources-" + label + ".json")).write_text(json.dumps(result, indent=2))
        return result

    def preservation(self, own):
        before = self.before
        after = self.snapshot("after")
        labeled_containers = self.container_snapshot(self.project)
        labeled_volumes = subprocess.check_output(["docker", "volume", "ls", "-q", "--filter",
            "label=com.docker.compose.project=" + self.project], text=True, timeout=30).split()
        own_volumes = {volume for row in own.values() for volume in row["volumes"]}
        differences = dict(
            remainingLabeledContainers=sorted(labeled_containers),
            remainingLabeledVolumes=sorted(labeled_volumes),
            remainingOwnContainers=sorted(set(own) & set(after["containers"])),
            remainingOwnVolumes=sorted(own_volumes & set(after["volumes"])),
            missingProtectedContainers=sorted(key for key, row in before["containers"].items()
                                              if not row["testcontainers"] and key not in after["containers"]),
            missingNamedVolumes=sorted(volume for volume in before["volumes"]
                                      if not re.fullmatch(r"[0-9a-f]{64}", volume) and volume not in after["volumes"]),
            removedEphemeralContainers=sorted(key for key, row in before["containers"].items()
                                             if row["testcontainers"] and key not in after["containers"]),
            removedAnonymousVolumes=sorted(volume for volume in before["volumes"]
                                          if re.fullmatch(r"[0-9a-f]{64}", volume) and volume not in after["volumes"]))
        passed = not any(differences[key] for key in ["remainingLabeledContainers", "remainingLabeledVolumes", "remainingOwnContainers", "remainingOwnVolumes",
                                                   "missingProtectedContainers", "missingNamedVolumes"])
        (self.directory / "preservation.json").write_text(json.dumps(dict(passed=passed, **differences), indent=2))
        self.log("PASS: preservation: own resources removed; protected containers and named volumes retained" if passed
                 else "FAIL: preservation; see preservation.json")
        return passed

    # --- lifecycle ---------------------------------------------------------------------------
    def boot(self):
        repo, infra, docker = self.repo, self.infra, self.docker
        self.fake.start()
        self.portone["PORTONE_API_BASE_URL"] = self.fake.base_url
        parsed = urllib.parse.urlsplit(self.fake.base_url)
        assert parsed.scheme == "http" and parsed.hostname == "127.0.0.1" and not parsed.path
        subprocess.run(["./gradlew", ":apps:auth:bootJar", ":apps:gateway:bootJar", ":apps:studio:bootJar", ":apps:review:bootJar",
                        ":apps:catalog:bootJar", ":apps:store:bootJar", ":apps:order:bootJar", ":apps:payment:bootJar",
                        ":apps:license:bootJar", ":apps:settlement:bootJar", ":e2e:compileTestJava", *self.gradle_network, "--no-daemon", "-q"],
                       check=True, cwd=repo, timeout=900)
        self.log("Isolated stack: " + self.project)
        subprocess.run(docker + ["up", "-d", "--pull", "never"], check=True, cwd=repo, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
        self.wait_for(lambda: self.ready(f"http://127.0.0.1:{infra['minio']}/minio/health/ready"), "MinIO")
        if self.tracing:
            self.wait_for(lambda: self.ready(self.tempo_url + "/ready"), "Tempo", 240)
        self.wait_for(lambda: subprocess.run(docker + ["exec", "-T", "mysql", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-proot1234"],
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30).returncode == 0, "MySQL")
        mc = docker + ["exec", "-T", "minio", "mc"]
        subprocess.run(mc + ["alias", "set", "admin", "http://localhost:9000", "stove", "stove1234"], check=True, stdout=subprocess.DEVNULL, timeout=60)
        subprocess.run(mc + ["mb", "admin/stove-builds"], check=True, stdout=subprocess.DEVNULL, timeout=60)
        policy = json.dumps({"Version": "2012-10-17", "Statement": [
            {"Effect": "Allow", "Action": ["s3:GetObject"], "Resource": ["arn:aws:s3:::stove-builds/*"]},
            {"Effect": "Allow", "Action": ["s3:ListBucket"], "Resource": ["arn:aws:s3:::stove-builds"]}]})
        subprocess.run(docker + ["exec", "-T", "minio", "sh", "-c", "cat > /tmp/read-policy.json"], input=policy, text=True, check=True, timeout=30)
        subprocess.run(mc + ["admin", "policy", "create", "admin", "review-build-read", "/tmp/read-policy.json"], check=True, stdout=subprocess.DEVNULL, timeout=60)
        subprocess.run(mc + ["admin", "user", "add", "admin", "review-reader", "isolated-read-only-password"], check=True, stdout=subprocess.DEVNULL, timeout=60)
        subprocess.run(mc + ["admin", "policy", "attach", "admin", "review-build-read", "--user", "review-reader"], check=True, stdout=subprocess.DEVNULL, timeout=60)

        public, offset = self.public, self.offset
        common = os.environ.copy()
        common.update(
            CATALOG_STORE_SERVICE_KEY=secrets.token_urlsafe(32), CATALOG_ORDER_SERVICE_KEY=secrets.token_urlsafe(32),
            CATALOG_SETTLEMENT_SERVICE_KEY=secrets.token_urlsafe(32), PAYMENT_ORDER_SERVICE_KEY=secrets.token_urlsafe(32),
            DB_HOST="127.0.0.1", DB_PORT=str(infra["mysql"]), DB_USER="stove", DB_PASSWORD="stove1234",
            REDIS_HOST="127.0.0.1", REDIS_PORT=str(infra["redis"]), KAFKA_BROKERS="127.0.0.1:" + str(infra["kafka"]),
            AUTH_ISSUER=public, JWT_ISSUER_URI=public, JWT_JWK_SET_URI=f"http://127.0.0.1:{8091 + offset}/oauth2/jwks",
            BFF_CLIENT_SECRET="isolated-journey-secret", BFF_PUBLIC_AUTH_BASE=public, BFF_REDIRECT_URI=public + "/login/oauth2/code/gateway-bff",
            STORAGE_ENDPOINT=f"http://127.0.0.1:{infra['minio']}", STORAGE_PRESIGN_ENDPOINT=f"http://127.0.0.1:{infra['minio']}",
            STORAGE_ACCESS_KEY="stove", STORAGE_SECRET_KEY="stove1234", STORAGE_ENCRYPTION_ENABLED="false",
            STUDIO_ASSET_PUBLIC_BASE_URL=public, TRACING_SAMPLE_RATE="0",
            AUTH_REVIEWER_EMAIL="reviewer-check@review.local", AUTH_REVIEWER_PASSWORD="reviewer-password-1234",
            AUTH_ADMIN_EMAIL=ADMIN_EMAIL, AUTH_ADMIN_PASSWORD=ADMIN_PASSWORD,
            REVIEW_STORAGE_ACCESS_KEY="review-reader", REVIEW_STORAGE_SECRET_KEY="isolated-read-only-password",
            DOWNLOAD_URI="http://127.0.0.1:9")
        if self.tracing:
            common.update(TRACING_SAMPLE_RATE="1", OTLP_ENDPOINT=f"http://127.0.0.1:{infra['otlp']}/v1/traces", **TRACE_EXPORT_ENV)
        for app, port in APPS.items():
            common[app.upper() + "_URI"] = f"http://127.0.0.1:{port + offset}"
        common["CATALOG_BASE_URL"] = common["CATALOG_URI"]
        common["LICENSE_BASE_URL"] = common["LICENSE_URI"]

        def start_app(app):
            env = dict(common)
            if app == "payment":
                env.update(self.portone)
                env.update(PAYMENT_PG_PROVIDER="PORTONE_TEST", PAYMENT_POLL_MS="1000", PAYMENT_RETRY_MS="5000",
                           PAYMENT_APPROVAL_PAYLOAD_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
                           PAYMENT_REFUND_EXECUTION_ENABLED="true", PAYMENT_REFUND_LOOKUP_ENABLED="true", PAYMENT_REFUND_RETRY_ENABLED="true")
            if app == "settlement":
                env.update({k: self.portone[k] for k in ["PORTONE_STORE_ID", "PORTONE_CHANNEL_KEY", "PORTONE_API_SECRET", "PORTONE_API_BASE_URL"]})
                env["PORTONE_API_SECRET"] = self.settlement_secret
                env.update(SETTLEMENT_PG_PROVIDER="PORTONE_TEST", SETTLEMENT_TOSSPAY_ENABLED="false")
            handle = open(self.directory / (app + ".log"), "a")
            process = subprocess.Popen(["java", "-Xmx320m", "-jar", str(application_jar(repo, app)), "--server.address=127.0.0.1", "--server.port=" + str(APPS[app] + offset)],
                                       env=env, cwd=repo, stdout=handle, stderr=subprocess.STDOUT)
            self.processes.append((app, process, handle))

        def apps_ready():
            for app, process, handle in self.processes:
                if process.poll() is not None:
                    raise AssertionError(app + " stopped; see " + str(self.directory / (app + ".log")))
            return all(self.ready(f"http://127.0.0.1:{APPS[app] + offset}/actuator/health") for app, _, _ in self.processes)

        for app in ["auth", "gateway", "studio", "review", "catalog"]:
            start_app(app)
        self.wait_for(apps_ready, "Auth/Gateway/Studio/Review/Catalog", 300)
        start_app("store")
        self.wait_for(apps_ready, "Store", 180)
        for app in ["order", "payment", "license"]:
            start_app(app)
        self.wait_for(apps_ready, "Order, Payment(PORTONE_TEST), License", 240)
        start_app("settlement")
        self.wait_for(apps_ready, "Settlement(PORTONE_TEST)", 180)

    def prepare(self):
        """Create, review and release a product and the buyer's first PENDING order through the e2e fixture."""
        evidence = self.directory / "license.json"
        self.set_step("prepare", "창작·심사·출시와 첫 주문을 준비하고 있습니다.")
        result = subprocess.run(["./gradlew", ":e2e:licenseE2eTest", "-Dstove.e2e.port-offset=" + str(self.offset),
                                 "-Dstove.e2e.kafka-brokers=127.0.0.1:" + str(self.infra["kafka"]),
                                 "-Dstove.e2e.license-evidence=" + str(evidence), "-Dstove.e2e.license-phase=preparePendingPurchase",
                                 *self.gradle_network, "--no-daemon", "-q"], cwd=self.repo, timeout=600)
        if result.returncode:
            raise RuntimeError("preparePendingPurchase failed")
        self.info.update(json.loads(evidence.read_text()))
        self.buyer = BffClient(self.public)
        subject = self.buyer.login(self.info["buyerEmail"], BUYER_PASSWORD)
        first = self.info["approved"]["orderNo"]
        with self.lock:
            self.state["buyerSubject"] = subject
            self.state["product"] = {k: self.info["product"][k] for k in ["productCode", "productId", "price", "contentVersion"]}
            self.state["orders"][first] = {"name": "first", "createdAt": time.time()}
            self.save_state()
        return subject, first

    def run(self, journey, success_message):
        """Boot, run ``journey(stack)`` and always tear down only this run's resources."""
        self.before = self.snapshot("before")
        started = time.monotonic()
        journey_passed, journey_error, failed_step, cleanup_errors = False, None, None, []

        def cleanup_step(label, action):
            try:
                return action()
            except Exception as error:
                cleanup_errors.append(label + ": " + str(error))
                try:
                    self.log("FAIL: cleanup " + cleanup_errors[-1])
                except Exception:
                    print("FAIL: cleanup " + label, flush=True)
                return None

        try:
            self.boot()
            journey(self)
            journey_passed = True
            self.log("PASS: " + success_message)
        except Exception as failure:
            journey_error = type(failure).__name__ + ": " + str(failure)
            failed_step = self.state["step"]
            cleanup_step("failure evidence", lambda: (self.directory / "failure.txt").write_text(traceback.format_exc()))
            cleanup_step("failure log", lambda: self.log("FAIL: " + journey_error))
            raise
        finally:
            # Compose removal lives in the outer finally, independently of all earlier
            # cleanup failures. Never replace an active journey exception.
            try:
                if self.buyer is not None:
                    cleanup_step("gateway audit", lambda: (self.directory / "gateway-audit.json").write_text(json.dumps(self.buyer.audit, indent=2)))
                for app, process, handle in self.processes:
                    cleanup_step(app + " terminate", process.terminate)
                for app, process, handle in self.processes:
                    def stop_and_wait(process=process):
                        try:
                            process.wait(timeout=40)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=10)
                    cleanup_step(app + " wait", stop_and_wait)
                    cleanup_step(app + " log close", handle.close)
                cleanup_step("fake stop", self.fake.stop)
                cleanup_step("fake requests", lambda: (self.directory / "fake-requests.json").write_text(json.dumps(self.fake.requests, indent=2)))
            finally:
                own = cleanup_step("own snapshot", lambda: self.container_snapshot(self.project))
                if own is not None:
                    cleanup_step("own evidence", lambda: (self.directory / "resources-own-before-down.json").write_text(json.dumps(own, indent=2)))
                cleanup_step("compose down", lambda: subprocess.run(self.docker + ["down", "--volumes", "--remove-orphans"], cwd=self.repo,
                    check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120))
                preserved = cleanup_step("preservation", lambda: self.preservation(own or {}))
                cleanup_passed = preserved is True and not cleanup_errors
                duration = round(time.monotonic() - started, 1)
                self.state.update(step="done" if journey_passed and cleanup_passed else "failed",
                                  stepText="Verification passed" if journey_passed and cleanup_passed else "Verification failed",
                                  journeyPassed=journey_passed, cleanupPassed=cleanup_passed)
                summary = dict(passed=journey_passed and cleanup_passed, journeyPassed=journey_passed, cleanupPassed=cleanup_passed,
                               checks=self.state["checks"], steps=len(self.state["checks"]), durationSeconds=duration,
                               failedStep=failed_step, error=journey_error, cleanupErrors=list(cleanup_errors))
                cleanup_step("final state", self.save_state)
                # Final evidence writes may themselves fail; report failure in summary.
                summary["cleanupPassed"] = cleanup_passed and not cleanup_errors
                summary["passed"] = journey_passed and summary["cleanupPassed"]
                summary["cleanupErrors"] = list(cleanup_errors)
                cleanup_step("final summary", lambda: (self.directory / "summary.json").write_text(json.dumps(summary, indent=2)))
                cleanup_step("duration log", lambda: self.log("Steps: " + str(len(self.state["checks"])) + "; duration seconds: " + str(duration)))
                if journey_passed and (not cleanup_passed or cleanup_errors):
                    raise RuntimeError("Cleanup/preservation failed: " + "; ".join(cleanup_errors))
