#!/usr/bin/env python3
"""Local fake-PortOne purchase/refund/settlement/repurchase verification."""
from pathlib import Path
import base64, re, json, os, secrets, socket, subprocess, sys, tempfile, threading, time, traceback, urllib.request, uuid

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/verification"))
from support.minio_image import ensure_minio_image
from support.boot_jars import application_jar  # noqa: E402
from support import ports as port_rules  # noqa: E402
from support.bff import BffClient  # noqa: E402

APPS = {"auth": 8091, "studio": 8085, "gateway": 8080, "review": 8086, "catalog": 8081, "store": 8087,
        "order": 8082, "payment": 8083, "license": 8084, "settlement": 8089}
OFFSET = port_rules.application_offset("verify-refund-settlement", "REFUND_SETTLEMENT_TEST_PORT_OFFSET", APPS.values())
from support.portone_fake import PortOneFake

# Reject every non-loopback urllib target, including redirects and inherited proxies.
class LocalOnly(urllib.request.BaseHandler):
    def default_open(self, request):
        parsed = urllib.parse.urlsplit(request.full_url)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
            raise RuntimeError("External HTTP target rejected")
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({}), LocalOnly()))

out_root = REPO / "runs"
out_root.mkdir(exist_ok=True)
directory = Path(tempfile.mkdtemp(prefix="refund-settlement-", dir=out_root))
project = directory.name
EVENTS = directory / "events.log"


def log(message):
    line = time.strftime("%H:%M:%S") + " " + message
    print(line, flush=True)
    with EVENTS.open("a") as handle:
        handle.write(line + "\n")


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


PORTONE = {key: secrets.token_urlsafe(24) for key in ["PORTONE_STORE_ID", "PORTONE_CHANNEL_KEY", "PORTONE_API_SECRET"]}
PORTONE.update(PORTONE_WEBHOOK_SECRET="whsec_" + base64.b64encode(secrets.token_bytes(32)).decode(),
               PORTONE_WEBHOOK_URL="https://webhook.invalid/api/v1/payments/webhooks/portone")
SETTLEMENT_SECRET = secrets.token_urlsafe(24)
GRADLE_NETWORK = [] if os.environ.get("GITHUB_ACTIONS") == "true" else ["--offline"]
fake = PortOneFake({"payment": PORTONE["PORTONE_API_SECRET"], "settlement": SETTLEMENT_SECRET}, PORTONE["PORTONE_STORE_ID"], PORTONE["PORTONE_CHANNEL_KEY"])
infra = {name: free_port() for name in ["mysql", "redis", "kafka", "minio"]}
minio_image = ensure_minio_image(REPO)
for image in ["mysql:8.0", "redis:7-alpine", "apache/kafka:3.9.0"]:
    subprocess.run(["docker", "image", "inspect", image], check=True, stdout=subprocess.DEVNULL, timeout=30)
log("Evidence: " + str(directory))

init = directory / "init.sql"
dbs = ["auth", "studio", "review", "catalog", "store", "order", "payment", "license", "settlement"]
init.write_text(" ".join("CREATE DATABASE stove_" + d + ";" for d in dbs) + " " +
                " ".join("GRANT ALL PRIVILEGES ON stove_" + d + ".* TO 'stove'@'%';" for d in dbs) + "\n")
compose = directory / "compose.yml"
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
    image: {minio_image}
    ports: ["127.0.0.1:{infra['minio']}:9000"]
    environment:
      MINIO_ROOT_USER: stove
      MINIO_ROOT_PASSWORD: stove1234
    command: ["server", "/data"]
""")
compose.chmod(0o600)
docker = ["docker", "compose", "-p", project, "-f", str(compose)]
processes = []
STATE = {"step": "starting", "stepText": "스택을 띄우고 있습니다.", "payable": False, "orderNo": None, "orders": {}, "checks": {}}
LOCK = threading.Lock()
buyer = None
journey_info = {}


def save_state():
    (directory / "state.json").write_text(json.dumps(STATE, indent=2, ensure_ascii=False))


def set_step(step, text, payable=False, order_no=None):
    with LOCK:
        STATE.update(step=step, stepText=text, payable=payable)
        if order_no is not None:
            STATE["orderNo"] = order_no
        save_state()
    log("STEP " + step + ": " + text)


def ready(url):
    try:
        with urllib.request.urlopen(url, timeout=3) as response:
            return response.status == 200
    except Exception:
        return False


def wait_for(check, description, seconds=180, interval=1):
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
    log("Ready: " + description)


def sql(query):
    return subprocess.check_output(docker + ["exec", "-T", "mysql", "mysql", "-ustove", "-pstove1234", "-N", "-e", query],
                                   text=True, stderr=subprocess.DEVNULL, timeout=30).strip()


def check(name, query, expected, seconds=120):
    wait_for(lambda: sql(query) == str(expected), name + " = " + str(expected), seconds, 2)
    with LOCK:
        STATE["checks"][name] = str(expected)
        save_state()



def create_order(name):
    product = journey_info["product"]
    request = {"requestId": str(uuid.uuid4()), "productCode": product["productCode"],
               "expectedContentVersion": product["contentVersion"], "expectedAmount": product["price"]}
    status, value = buyer.call("POST", "/api/v1/orders", request)
    assert status == 200, "order create HTTP " + str(status)
    order_no = value["data"]["orderNo"]
    with LOCK:
        STATE["orders"][order_no] = {"name": name, "createdAt": time.time()}
        save_state()
    log("Order " + name + " created: " + order_no)
    return order_no


def pay(name, order_no, label):
    wait_for(lambda: buyer.data("/api/v1/payments/" + order_no)["status"] == "PENDING", "payment PENDING " + order_no, 120)
    set_step(name, label, order_no=order_no)
    checkout = buyer.data("/api/v1/payments/" + order_no + "/checkout")
    request = urllib.request.Request(fake.base_url + "/_fake/sdk/request-payment", data=json.dumps(checkout).encode(),
        headers={"Authorization": "PortOne " + PORTONE["PORTONE_API_SECRET"], "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=5) as response:
        sdk = json.load(response)
    status, _ = buyer.call("POST", "/api/v1/payments/" + order_no + "/approvals", dict(requestId=str(uuid.uuid4()), **sdk))
    assert status == 202, "approval HTTP " + str(status)
    wait_for(lambda: buyer.data("/api/v1/orders/" + order_no)["status"] == "PAID", "order PAID " + order_no, 180)
    return order_no


def register(order_no):
    def registered():
        storage = buyer.data("/api/v1/storage?page=0&size=50")
        item = next((i for i in storage["content"] if i["orderNo"] == order_no), None)
        if item is None:
            return False
        status, value = buyer.call("POST", "/api/v1/storage/" + item["licenseId"] + "/registration")
        assert status == 200, "registration HTTP " + str(status) + " " + json.dumps(value, ensure_ascii=False)
        return True
    wait_for(registered, "registered " + order_no, 120, 2)
    library = buyer.data("/api/v1/library/" + journey_info["product"]["productCode"])
    assert library["status"] == "ACTIVE"
    with LOCK:
        STATE["orders"][order_no]["licenseId"] = library["licenseId"]
        save_state()
    return library["licenseId"]


def container_snapshot(project_filter=None):
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


def snapshot(label):
    result = dict(containers=container_snapshot(),
                  volumes=sorted(subprocess.check_output(["docker", "volume", "ls", "-q"], text=True, timeout=30).split()))
    (directory / ("resources-" + label + ".json")).write_text(json.dumps(result, indent=2))
    return result


def preservation(own):
    after = snapshot("after")
    own_volumes = {volume for row in own.values() for volume in row["volumes"]}
    differences = dict(
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
    passed = not any(differences[key] for key in ["remainingOwnContainers", "remainingOwnVolumes",
                                               "missingProtectedContainers", "missingNamedVolumes"])
    (directory / "preservation.json").write_text(json.dumps(dict(passed=passed, **differences), indent=2))
    log(("PASS" if passed else "FAIL") + ": preservation: own resources removed; protected containers and named volumes retained" if passed
        else "FAIL: preservation; see preservation.json")
    return passed


before = snapshot("before")
started = time.monotonic()
journey_passed = False
try:
    fake.start()
    PORTONE["PORTONE_API_BASE_URL"] = fake.base_url
    parsed = urllib.parse.urlsplit(fake.base_url)
    assert parsed.scheme == "http" and parsed.hostname == "127.0.0.1" and not parsed.path
    subprocess.run(["./gradlew", ":apps:auth:bootJar", ":apps:gateway:bootJar", ":apps:studio:bootJar", ":apps:review:bootJar",
                    ":apps:catalog:bootJar", ":apps:store:bootJar", ":apps:order:bootJar", ":apps:payment:bootJar",
                    ":apps:license:bootJar", ":apps:settlement:bootJar", ":e2e:compileTestJava", *GRADLE_NETWORK, "--no-daemon", "-q"],
                   check=True, cwd=REPO, timeout=900)
    log("Isolated stack: " + project)
    subprocess.run(docker + ["up", "-d", "--pull", "never"], check=True, cwd=REPO, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    wait_for(lambda: ready(f"http://127.0.0.1:{infra['minio']}/minio/health/ready"), "MinIO")
    wait_for(lambda: subprocess.run(docker + ["exec", "-T", "mysql", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-proot1234"],
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

    public = f"http://127.0.0.1:{8080 + OFFSET}"
    common = os.environ.copy()
    common.update(
        CATALOG_STORE_SERVICE_KEY=secrets.token_urlsafe(32), CATALOG_ORDER_SERVICE_KEY=secrets.token_urlsafe(32),
        CATALOG_SETTLEMENT_SERVICE_KEY=secrets.token_urlsafe(32), PAYMENT_ORDER_SERVICE_KEY=secrets.token_urlsafe(32),
        DB_HOST="127.0.0.1", DB_PORT=str(infra["mysql"]), DB_USER="stove", DB_PASSWORD="stove1234",
        REDIS_HOST="127.0.0.1", REDIS_PORT=str(infra["redis"]), KAFKA_BROKERS="127.0.0.1:" + str(infra["kafka"]),
        AUTH_ISSUER=public, JWT_ISSUER_URI=public, JWT_JWK_SET_URI=f"http://127.0.0.1:{8091 + OFFSET}/oauth2/jwks",
        BFF_CLIENT_SECRET="isolated-journey-secret", BFF_PUBLIC_AUTH_BASE=public, BFF_REDIRECT_URI=public + "/login/oauth2/code/gateway-bff",
        STORAGE_ENDPOINT=f"http://127.0.0.1:{infra['minio']}", STORAGE_PRESIGN_ENDPOINT=f"http://127.0.0.1:{infra['minio']}",
        STORAGE_ACCESS_KEY="stove", STORAGE_SECRET_KEY="stove1234", STORAGE_ENCRYPTION_ENABLED="false",
        STUDIO_ASSET_PUBLIC_BASE_URL=public, TRACING_SAMPLE_RATE="0",
        AUTH_REVIEWER_EMAIL="reviewer-check@review.local", AUTH_REVIEWER_PASSWORD="reviewer-password-1234",
        AUTH_ADMIN_EMAIL="admin-check@review.local", AUTH_ADMIN_PASSWORD="admin-password-1234",
        REVIEW_STORAGE_ACCESS_KEY="review-reader", REVIEW_STORAGE_SECRET_KEY="isolated-read-only-password",
        DOWNLOAD_URI="http://127.0.0.1:9")
    for app, port in APPS.items():
        common[app.upper() + "_URI"] = f"http://127.0.0.1:{port + OFFSET}"
    common["CATALOG_BASE_URL"] = common["CATALOG_URI"]
    common["LICENSE_BASE_URL"] = common["LICENSE_URI"]

    def start_app(app):
        env = dict(common)
        if app == "payment":
            env.update(PORTONE)
            env.update(PAYMENT_PG_PROVIDER="PORTONE_TEST", PAYMENT_POLL_MS="1000", PAYMENT_RETRY_MS="5000",
                       PAYMENT_APPROVAL_PAYLOAD_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
                       PAYMENT_REFUND_EXECUTION_ENABLED="true", PAYMENT_REFUND_LOOKUP_ENABLED="true", PAYMENT_REFUND_RETRY_ENABLED="true")
        if app == "settlement":
            env.update({k: PORTONE[k] for k in ["PORTONE_STORE_ID", "PORTONE_CHANNEL_KEY", "PORTONE_API_SECRET", "PORTONE_API_BASE_URL"]})
            env["PORTONE_API_SECRET"] = SETTLEMENT_SECRET
            env.update(SETTLEMENT_PG_PROVIDER="PORTONE_TEST", SETTLEMENT_TOSSPAY_ENABLED="false")
        handle = open(directory / (app + ".log"), "a")
        process = subprocess.Popen(["java", "-Xmx320m", "-jar", str(application_jar(REPO, app)), "--server.address=127.0.0.1", "--server.port=" + str(APPS[app] + OFFSET)],
                                   env=env, cwd=REPO, stdout=handle, stderr=subprocess.STDOUT)
        processes.append((app, process, handle))

    def apps_ready():
        for app, process, handle in processes:
            if process.poll() is not None:
                raise AssertionError(app + " stopped; see " + str(directory / (app + ".log")))
        return all(ready(f"http://127.0.0.1:{APPS[app] + OFFSET}/actuator/health") for app, _, _ in processes)

    for app in ["auth", "gateway", "studio", "review", "catalog"]:
        start_app(app)
    wait_for(apps_ready, "Auth/Gateway/Studio/Review/Catalog", 300)
    start_app("store")
    wait_for(apps_ready, "Store", 180)
    for app in ["order", "payment", "license"]:
        start_app(app)
    wait_for(apps_ready, "Order, Payment(PORTONE_TEST), License", 240)
    start_app("settlement")
    wait_for(apps_ready, "Settlement(PORTONE_TEST)", 180)

    evidence = directory / "license.json"
    set_step("prepare", "창작·심사·출시와 첫 주문을 준비하고 있습니다.")
    result = subprocess.run(["./gradlew", ":e2e:licenseE2eTest", "-Dstove.e2e.port-offset=" + str(OFFSET),
                             "-Dstove.e2e.kafka-brokers=127.0.0.1:" + str(infra["kafka"]),
                             "-Dstove.e2e.license-evidence=" + str(evidence), "-Dstove.e2e.license-phase=preparePendingPurchase",
                             *GRADLE_NETWORK, "--no-daemon", "-q"], cwd=REPO, timeout=600)
    if result.returncode:
        raise RuntimeError("preparePendingPurchase failed")
    journey_info.update(json.loads(evidence.read_text()))
    buyer = BffClient(public)
    subject = buyer.login(journey_info["buyerEmail"], "creator-password-1234")
    with LOCK:
        STATE["buyerSubject"] = subject
        STATE["product"] = {k: journey_info["product"][k] for k in ["productCode", "productId", "price", "contentVersion"]}
        first = journey_info["approved"]["orderNo"]
        STATE["orders"][first] = {"name": "first", "createdAt": time.time()}
        save_state()

    # 1. First purchase (fake SDK approval) -> register.
    first = pay("first", first, "첫 구매")
    first_license = register(first)
    check("first sale", "SELECT COUNT(*) FROM stove_settlement.settlement_sale WHERE order_no='" + first + "'", 1, 180)

    # 2. Automatic refund (within the window) -> local fake cancel -> completed.
    set_step("refund", "첫 구매를 환불하고 있습니다. 로컬 PortOne fake 취소 경로를 검증합니다.")
    status, value = buyer.call("POST", "/api/v1/orders/" + first + "/refund")
    assert status in {200, 202}, "refund HTTP " + str(status) + " " + json.dumps(value, ensure_ascii=False)
    wait_for(lambda: buyer.data("/api/v1/orders/" + first + "/refund")["status"] == "COMPLETED", "refund COMPLETED", 120, 2)
    check("PurchaseRefunded", "SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='PurchaseRefunded' AND aggregate_id='" + first + "'", 1)
    check("license refunded", "SELECT status FROM stove_license.product_license WHERE order_no='" + first + "'", "REFUNDED")
    check("registration ended", "SELECT GROUP_CONCAT(status) FROM stove_license.license_registration WHERE order_no='" + first + "'", "ENDED")
    check("settlement refund", "SELECT COUNT(*) FROM stove_settlement.settlement_refund WHERE order_no='" + first + "'", 1, 120)
    with LOCK:
        STATE["refund"] = {
            "order": buyer.data("/api/v1/orders/" + first + "/refund"),
            "settlementRefund": sql("SELECT CONCAT_WS('|',settlement_month,amount,seller_payout_amount) FROM stove_settlement.settlement_refund WHERE order_no='" + first + "'"),
            "monthly": sql("SELECT CONCAT_WS('|',settlement_month,paid_amount,refund_amount,seller_payout_amount,refunded_seller_payout_amount) FROM stove_settlement.seller_monthly_settlement"),
        }
        save_state()

    # 3. Repurchase (fake SDK approval) -> register as a new license.
    again = create_order("repurchase")
    again = pay("repurchase", again, "재구매")
    again_license = register(again)
    assert again_license != first_license
    check("one ACTIVE license", "SELECT COUNT(*) FROM stove_license.license_registration WHERE status='ACTIVE' AND order_no='" + again + "' AND buyer_subject='" + subject + "'", 1)
    check("repurchase sale", "SELECT COUNT(*) FROM stove_settlement.settlement_sale WHERE order_no='" + again + "'", 1, 180)
    with LOCK:
        STATE["summary"] = {"first": first, "firstLicense": first_license, "repurchase": again, "repurchaseLicense": again_license,
                            "sales": sql("SELECT GROUP_CONCAT(CONCAT_WS('|',order_no,settlement_month,amount,seller_payout_amount) ORDER BY id) FROM stove_settlement.settlement_sale"),
                            "monthlyAfter": sql("SELECT CONCAT_WS('|',settlement_month,paid_amount,refund_amount,seller_payout_amount,refunded_seller_payout_amount) FROM stove_settlement.seller_monthly_settlement")}
        save_state()
    for order in [first, again]:
        payment_id = buyer.data("/api/v1/payments/" + order)["paymentId"]
        confirms = [r for r in fake.requests if r["method"] == "POST" and r["path"] == "/payments/" + payment_id + "/confirm" and r["status"] == 200]
        assert len(confirms) == 1, "exactly one confirm per paid order"
    cancels = [r for r in fake.requests if r["method"] == "POST" and r["path"].endswith("/cancel")]
    assert len(cancels) == 1 and cancels[0]["status"] == 200
    refund_id = STATE["refund"]["order"]["refundId"]
    assert cancels[0]["amount"] == journey_info["product"]["price"]
    assert cancels[0]["reason"] == "ESD refund " + refund_id
    first_payment = buyer.data("/api/v1/payments/" + first)["paymentId"]
    assert cancels[0]["path"] == "/payments/" + first_payment + "/cancel"
    for order in [first, again]:
        payment_id = buyer.data("/api/v1/payments/" + order)["paymentId"]
        assert any(r["caller"] == "settlement" and r["method"] == "GET" and r["status"] == 200
                   and r["path"].startswith("/payments/" + payment_id + "?") for r in fake.requests)
        log("PASS: settlement GET payment for order " + order)
    assert not any(r["caller"] == "settlement" and r["method"] == "POST" for r in fake.requests)
    assert all(r["caller"] == "payment" for r in fake.requests if r["path"].endswith(("/confirm", "/cancel")))
    log("PASS: settlement POST=0; all confirm/cancel callers=payment")
    (directory / "summary.json").write_text(json.dumps(dict(passed=True, checks=STATE["checks"], steps=len(STATE["checks"]), durationSeconds=round(time.monotonic()-started, 1)), indent=2))
    journey_passed = True
    set_step("done", "여정을 모두 마쳤습니다.")
    log("PASS: local PortOne fake purchase -> register -> refund (fake cancel) -> License ended -> Settlement refund -> repurchase -> register")
except Exception as failure:
    with LOCK:
        STATE["failure"] = type(failure).__name__ + ": " + str(failure)
        save_state()
    set_step("failed", "여정이 실패했습니다: " + str(failure))
    (directory / "failure.txt").write_text(traceback.format_exc())
    (directory / "summary.json").write_text(json.dumps(dict(passed=False, failedStep=STATE["step"], error=str(failure), steps=len(STATE["checks"]), durationSeconds=round(time.monotonic()-started, 1)), indent=2))
    log("FAIL: " + str(failure))
    raise
finally:
    if buyer is not None:
        (directory / "gateway-audit.json").write_text(json.dumps(buyer.audit, indent=2))
    for app, process, handle in processes:
        process.terminate()
    for app, process, handle in processes:
        try:
            process.wait(timeout=40)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
        handle.close()
    fake.stop()
    (directory / "fake-requests.json").write_text(json.dumps(fake.requests, indent=2))
    try:
        own = container_snapshot(project)
        (directory / "resources-own-before-down.json").write_text(json.dumps(own, indent=2))
        down = subprocess.run(docker + ["down", "--volumes", "--remove-orphans"], cwd=REPO,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
        preserved = preservation(own)
        if down.returncode or not preserved:
            raise RuntimeError("Project cleanup/preservation failed; see preservation.json")
    except Exception as cleanup_failure:
        log("FAIL: preservation: " + str(cleanup_failure))
        if journey_passed:
            raise
    finally:
        log("Duration seconds: " + str(round(time.monotonic() - started, 1)))
