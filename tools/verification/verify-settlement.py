#!/usr/bin/env python3
"""Verify Settlement and the external PG against the ten completed services in an isolated, disposable stack."""

from pathlib import Path
from support.minio_image import ensure_minio_image
from support.mock_pg_runtime import MockPgRuntime
import os, socket, subprocess, time, urllib.request, tempfile, json, secrets
repo=Path(__file__).resolve().parents[2]
output_root=repo/"runs"
output_root.mkdir(exist_ok=True)
directory=Path(tempfile.mkdtemp(prefix="stove-settlement-check-",dir=output_root))
project=directory.name
offset=int(os.environ.get("SETTLEMENT_TEST_PORT_OFFSET","33000"))
apps={"auth":8091,"studio":8085,"gateway":8080,"review":8086,"catalog":8081,"store":8087,"order":8082,"payment":8083,"license":8084,"download":8088,"settlement":8089}
for port in apps.values():
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        sock.bind(("127.0.0.1",port+offset))
def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        return sock.getsockname()[1]
ports={name:free_port() for name in ["mysql","redis","kafka","minio"]}
minio_image=ensure_minio_image(repo)
subprocess.run(["./gradlew",":simulators:mock-pg:bootJar",":apps:auth:bootJar",":apps:gateway:bootJar",":apps:studio:bootJar",":apps:review:bootJar",":apps:catalog:bootJar",":apps:store:bootJar",":apps:order:bootJar",":apps:payment:bootJar",":apps:license:bootJar",":apps:download:bootJar",":apps:settlement:bootJar",":apps:settlement:writeVerificationClasspath",":e2e:compileTestJava","--offline","--no-daemon"],check=True,cwd=repo)
init=directory/"init.sql"
init.write_text("CREATE DATABASE stove_auth; CREATE DATABASE stove_studio; CREATE DATABASE stove_review; CREATE DATABASE stove_catalog; CREATE DATABASE stove_store; CREATE DATABASE stove_order; CREATE DATABASE stove_payment; CREATE DATABASE stove_license; CREATE DATABASE stove_settlement; GRANT ALL PRIVILEGES ON stove_auth.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_studio.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_review.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_catalog.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_store.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_order.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_payment.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_license.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_settlement.* TO 'stove'@'%';\n")
pg=MockPgRuntime(repo,directory)
compose=directory/"compose.yml"
compose.write_text(f"""services:
{pg.compose_service()}  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root1234
      MYSQL_USER: stove
      MYSQL_PASSWORD: stove1234
    ports: ["127.0.0.1:{ports['mysql']}:3306"]
    volumes: ["{init}:/docker-entrypoint-initdb.d/01-studio.sql:ro"]
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot1234"]
      interval: 2s
      timeout: 2s
      retries: 60
  redis:
    image: redis:7-alpine
    ports: ["127.0.0.1:{ports['redis']}:6379"]
  kafka:
    image: apache/kafka:3.9.0
    ports: ["127.0.0.1:{ports['kafka']}:{ports['kafka']}"]
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:19092,CONTROLLER://:9093,EXTERNAL://:{ports['kafka']}
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:19092,EXTERNAL://localhost:{ports['kafka']}
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
    ports: ["127.0.0.1:{ports['minio']}:9000"]
    environment:
      MINIO_ROOT_USER: stove
      MINIO_ROOT_PASSWORD: stove1234
    command: ["server", "/data"]
""")
docker=["docker","compose","-p",project,"-f",str(compose)]
pg.docker=docker
compose.chmod(0o600)
processes=[]
(directory/"containers-before.txt").write_text(subprocess.check_output(["docker","ps","--format","{{.ID}} {{.Names}}"],text=True))
def ready(url):
    try:
        with urllib.request.urlopen(url,timeout=3) as response: return response.status==200
    except Exception: return False
def wait_for(check,description,seconds=180):
    deadline=time.monotonic()+seconds
    while not check():
        if time.monotonic()>deadline: raise RuntimeError("Timed out: "+description)
        time.sleep(1)
    print("Ready: "+description,flush=True)
try:
    print("Isolated verification: "+str(directory),flush=True)
    subprocess.run(docker+["up","-d"],check=True,cwd=repo)
    wait_for(lambda:ready(f"http://127.0.0.1:{ports['minio']}/minio/health/ready"),"MinIO")
    wait_for(lambda:subprocess.run(docker+["exec","-T","mysql","mysqladmin","ping","-h","127.0.0.1","-uroot","-proot1234"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0,"MySQL")
    # MinIO's bundled mc creates a read-only account for this disposable stack.
    mc=docker+["exec","-T","minio","mc"]
    subprocess.run(mc+["alias","set","admin","http://localhost:9000","stove","stove1234"],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(mc+["mb","admin/stove-builds"],check=True,stdout=subprocess.DEVNULL)
    policy=json.dumps({"Version":"2012-10-17","Statement":[
        {"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::stove-builds/*"]},
        {"Effect":"Allow","Action":["s3:ListBucket"],"Resource":["arn:aws:s3:::stove-builds"]}]})
    subprocess.run(docker+["exec","-T","minio","sh","-c","cat > /tmp/review-read-policy.json"],input=policy,text=True,check=True)
    subprocess.run(mc+["admin","policy","create","admin","review-build-read","/tmp/review-read-policy.json"],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(mc+["admin","user","add","admin","review-reader","isolated-read-only-password"],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(mc+["admin","policy","attach","admin","review-build-read","--user","review-reader"],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(mc+["alias","set","reader","http://localhost:9000","review-reader","isolated-read-only-password"],check=True,stdout=subprocess.DEVNULL)
    denied=subprocess.run(mc+["pipe","reader/stove-builds/write-must-fail.zip"],input=b"forbidden",stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if denied.returncode==0: raise RuntimeError("Review storage account unexpectedly has write access")
    subprocess.run(mc+["pipe","admin/stove-builds/iam-check.bin"],input=b"read-only fixture",check=True,stdout=subprocess.DEVNULL)
    denied_delete=subprocess.run(mc+["rm","reader/stove-builds/iam-check.bin"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if denied_delete.returncode==0: raise RuntimeError("Review storage account unexpectedly has delete access")
    subprocess.run(mc+["stat","admin/stove-builds/iam-check.bin"],check=True,stdout=subprocess.DEVNULL)
    print("PASS: Review storage account cannot upload or delete",flush=True)
    subprocess.run(mc+["admin","user","add","admin","download-reader","isolated-read-only-password"],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(mc+["admin","policy","attach","admin","review-build-read","--user","download-reader"],check=True,stdout=subprocess.DEVNULL)

    pg.start()
    public=f"http://127.0.0.1:{8080+offset}"
    service_key=secrets.token_urlsafe(32)
    settlement_service_key=secrets.token_urlsafe(32)
    common=os.environ.copy()
    common.update(DB_HOST="127.0.0.1",DB_PORT=str(ports["mysql"]),DB_USER="stove",DB_PASSWORD="stove1234",
        REDIS_HOST="127.0.0.1",REDIS_PORT=str(ports["redis"]),KAFKA_BROKERS="127.0.0.1:"+str(ports["kafka"]),
        CATALOG_DOWNLOAD_SERVICE_KEY=service_key,AUTH_ISSUER=public,JWT_ISSUER_URI=public,JWT_JWK_SET_URI=f"http://127.0.0.1:{8091+offset}/oauth2/jwks",
        BFF_CLIENT_SECRET="isolated-studio-test-secret",BFF_PUBLIC_AUTH_BASE=public,
        BFF_REDIRECT_URI=public+"/login/oauth2/code/gateway-bff",
        STORAGE_ENDPOINT=f"http://127.0.0.1:{ports['minio']}",STORAGE_PRESIGN_ENDPOINT=f"http://127.0.0.1:{ports['minio']}",
        STORAGE_ACCESS_KEY="stove",STORAGE_SECRET_KEY="stove1234",STORAGE_ENCRYPTION_ENABLED="false",
        STUDIO_ASSET_PUBLIC_BASE_URL=public,TRACING_SAMPLE_RATE="0",
        AUTH_REVIEWER_EMAIL="reviewer-check@review.local",AUTH_REVIEWER_PASSWORD="reviewer-password-1234",
        AUTH_ADMIN_EMAIL="admin-check@review.local",AUTH_ADMIN_PASSWORD="admin-password-1234",
        REVIEW_STORAGE_ACCESS_KEY="review-reader",REVIEW_STORAGE_SECRET_KEY="isolated-read-only-password")
    for app,port in apps.items(): common[app.upper()+"_URI"]=f"http://127.0.0.1:{port+offset}"
    def start_app(app,arguments=(),fault=False):
        port=apps[app]
        app_environment=pg.client_environment(app,common)
        if app in {"catalog","settlement"}: app_environment["CATALOG_SETTLEMENT_SERVICE_KEY"]=settlement_service_key
        jar=next(path for path in (repo/"apps"/app/"build/libs").glob("*.jar") if not path.name.endswith("-plain.jar"))
        log=open(directory/(app+".log"),"a")
        launch=["-jar",str(jar)]
        if fault:
            assert app=="settlement"
            classpath=(repo/"apps/settlement/build/verification-classpath.txt").read_text()
            launch=["-cp",classpath,"com.stove.settlement.SettlementServiceApplication",
                "--spring.main.sources=com.stove.settlement.config.verification.SettlementFaultConfiguration"]
        process=subprocess.Popen(["java","-Xmx256m",*launch,"--server.port="+str(port+offset),*arguments],env=app_environment,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
        processes.append((app,process,log))
    common["CATALOG_BASE_URL"]="http://127.0.0.1:"+str(apps["catalog"]+offset)
    def stop_app(name):
        item=next(item for item in processes if item[0]==name)
        app,process,log=item
        process.terminate()
        try: process.wait(timeout=40)
        except subprocess.TimeoutExpired: process.kill();process.wait()
        log.close();processes.remove(item)
    for app in apps:
        if app not in {"store", "order", "payment", "license", "download", "settlement"}: start_app(app)
    def apps_ready():
        for app,process,log in processes:
            if process.poll() is not None: raise RuntimeError(app+" stopped. See "+str(directory/(app+".log")))
        return all(ready(f"http://127.0.0.1:{apps[app]+offset}/actuator/health") for app,process,log in processes)
    wait_for(apps_ready,"Auth/Gateway/Studio/Review/Catalog",240)
    start_app("store")
    wait_for(apps_ready,"Store",180)
    def verify(task,*extra):
        result=subprocess.run(["./gradlew",task,"-Dstove.e2e.port-offset="+str(offset),
            "-Dstove.e2e.kafka-brokers=127.0.0.1:"+str(ports["kafka"]),*extra,"--offline","--no-daemon"],cwd=repo)
        if result.returncode: raise SystemExit(result.returncode)
    start_app("order")
    start_app("payment")
    start_app("license")
    wait_for(apps_ready,"Order, Payment, License",180)
    common.update(LICENSE_BASE_URL=f"http://127.0.0.1:{8084+offset}",
        CATALOG_DOWNLOAD_SERVICE_KEY=service_key,STORAGE_BUCKET="stove-builds",
        DOWNLOAD_STORAGE_ACCESS_KEY="download-reader",DOWNLOAD_STORAGE_SECRET_KEY="isolated-read-only-password",
        DOWNLOAD_BUILD_URL_TTL="PT5M")
    start_app("download"); wait_for(apps_ready,"Download without MongoDB",180)
    start_app("settlement"); wait_for(apps_ready,"Settlement",180)
    evidence="-Dstove.e2e.settlement-evidence="+str(directory/"settlement.json")
    phases=[]
    def phase(name,label=None):
        label=label or name
        verify(":e2e:settlementE2eTest", "-Dstove.e2e.settlement-phase="+name, evidence)
        import shutil
        shutil.copytree(repo/"e2e/build/test-results/settlementE2eTest",directory/("junit-"+label),dirs_exist_ok=True)
        phases.append(label)
        print("PASS: "+label,flush=True)
    def sql(query):
        return subprocess.check_output(docker+["exec","-T","mysql","mysql","-ustove","-pstove1234","-N","-e",query],text=True,stderr=subprocess.DEVNULL).strip()
    def check(query,expected,description):
        wait_for(lambda:sql(query)==str(expected),description,90)
    def restart(app,arguments=()):
        stop_app(app); start_app(app,arguments); wait_for(apps_ready,app+" restarted",180)
    import uuid
    state_file=directory/"settlement.json"
    def state(): return json.loads(state_file.read_text())
    def update(**values):
        data=state(); data.update(values); state_file.write_text(json.dumps(data)); state_file.chmod(0o600)
    def latest_order(): return str(uuid.UUID(state()["latestOrderNo"]))
    def payout():
        request_id=str(uuid.uuid4()); report=pg.run(request_id)
        assert pg.run(request_id)==report
        update(activeReceiptId=report["providerSettlementId"])
        return report
    def replay_evidence():
        payload=json.loads(sql("SELECT payload FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND aggregate_id='"+latest_order()+"'"))
        update(replayEvent=payload)
        return payload
    phase("initialPurchasesAndDownloads")
    phase("allocationAndOwnershipAreIsolated")
    report=payout()
    assert (report["recordCount"],report["paymentAmountTotal"],report["pgFeeAmountTotal"],report["payoutAmount"])==(3,30000,900,29100)
    phase("collectsPayoutAndRetriesWithoutDuplicatingAmounts")
    pg.stop(); stop_app("catalog")
    phase("storedReadsAndReconciliationSurviveExternalOutage")
    pg.start(); start_app("catalog"); wait_for(apps_ready,"Catalog recovered",180)

    stop_app("settlement")
    phase("paidPurchaseWhileSettlementIsStopped")
    check("SELECT COUNT(*) FROM stove_settlement.settlement_sale",3,"Settlement stopped without blocking purchase")
    marker=directory/"settlement-after-commit"
    start_app("settlement",["--stove.verification.commit-marker="+str(marker)],True)
    wait_for(marker.exists,"Settlement committed before process halt and consumer ACK",90)
    item=next(item for item in processes if item[0]=="settlement")
    app,process,log=item
    assert process.wait(timeout=20)==0
    log.close(); processes.remove(item)
    event_id=sql("SELECT event_id FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND aggregate_id='"+latest_order()+"'")
    assert marker.read_text()==event_id
    committed=sql("SELECT CONCAT(id,'|',received_at) FROM stove_settlement.settlement_sale WHERE order_no='"+latest_order()+"'")
    check("SELECT COUNT(*) FROM stove_settlement.settlement_sale",4,"Four sales committed before ACK")
    start_app("settlement"); wait_for(apps_ready,"Settlement catches up",180)
    update(expectedSales=4); phase("latestSaleArrivesExactlyOnce","catch-up")
    assert sql("SELECT CONCAT(id,'|',received_at) FROM stove_settlement.settlement_sale WHERE order_no='"+latest_order()+"'")==committed

    restart("payment",["--stove.payment.processing-enabled=false"])
    phase("preparesPendingPurchase")
    check("SELECT COUNT(*) FROM stove_payment.purchase_payment WHERE status='PENDING'",1,"Payment receipt persisted")
    restart("order",["--stove.outbox.relay-enabled=false"])
    restart("payment")
    phase("pendingPurchaseBecomesPaid")
    check("SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND status='PENDING'",1,"Original OrderPaid held in Outbox")
    stop_app("payment")
    report=payout(); assert report["recordCount"]==2
    phase("payoutBeforeOrderPaidWaitsForMissingSale")
    restart("order")
    update(expectedSales=5); phase("latestSaleArrivesExactlyOnce","without-payment")
    pg.stop(); stop_app("catalog")
    phase("storedPayoutReconcilesAfterDelayedSale")
    restart("settlement")
    phase("storedPayoutReconcilesAfterDelayedSale","receipt-restart")
    pg.start(); start_app("catalog"); start_app("payment"); wait_for(apps_ready,"External providers and Payment recovered",180)

    stop_app("settlement")
    phase("paidPurchaseWhileSettlementIsStopped","before-provider-outage")
    replay_evidence(); pg.stop()
    start_app("settlement"); wait_for(apps_ready,"Settlement with unavailable PG",180)
    phase("providerFailureReachesDltWithOriginalPaidEvidence")
    check("SELECT COUNT(*) FROM stove_settlement.settlement_sale",5,"No sale without PG evidence")
    pg.start()
    phase("replaysOriginalOrderPaidAfterProviderRecovery")
    update(expectedSales=6); phase("latestSaleArrivesExactlyOnce","original-replay")

    pg.start("DECLINED")
    phase("declinedPurchaseDoesNotCreateASale")
    check("SELECT COUNT(*) FROM stove_settlement.settlement_sale",6,"Decline has no sale")
    pg.start()
    subprocess.run(docker+["stop","pg-mysql"],check=True)
    phase("preparesPendingPurchase","pg-database-outage")
    check("SELECT COUNT(*) FROM stove_payment.purchase_payment WHERE status='PENDING'",1,"PG DB outage leaves Payment pending")
    subprocess.run(docker+["start","pg-mysql"],check=True)
    wait_for(lambda:ready(pg.uri+"/actuator/health"),"PG database recovered",90)
    phase("pendingPurchaseBecomesPaid","pg-database-recovery")
    update(expectedSales=7); phase("latestSaleArrivesExactlyOnce","pg-database-sale")
    report=payout(); assert report["recordCount"]==2
    phase("allReceiptsAndAllocationsAgree")
    check("SELECT COUNT(*) FROM stove_settlement.settlement_sale",7,"Seven immutable sales")
    assert sql("SELECT SUM(amount),SUM(seller_payout_amount),SUM(pg_fee_amount),SUM(platform_net_amount) FROM stove_settlement.settlement_sale")=="70000\t63000\t2100\t4900"
    assert sql("SELECT SUM(payout_amount) FROM stove_settlement.pg_receipt WHERE status='SIMULATED_RECEIVED'")=="67900"
    assert sql("SELECT COUNT(*) FROM stove_settlement.processed_event WHERE consumer_group='settlement-paid-order'")=="7"
    assert sql("SELECT COUNT(*) FROM stove_payment.simulated_payment_charge")=="0"
    assert pg.sql("SELECT COUNT(*) FROM pg_charge")=="8"
    assert pg.sql("SELECT COUNT(*) FROM pg_settlement_item")=="7"
    summary={"phases":len(phases),"checks":phases,"paidOrders":7,"failedOrders":1,"sales":7,
        "supplierProcesses":1,"supplierDatabaseSeparate":True,"payoutReports":3,"simulatedReceived":67900,
        "paidAmount":70000,"sellerShare":63000,"pgCost":2100,"platformRemainder":4900,
        "paymentRelayRequired":False,"commitBeforeAckRecovered":True,"actualBankTransfers":False}
    (directory/"summary.json").write_text(json.dumps(summary,indent=2))
    print("PASS: eleven business apps + external PG; purchase/download; allocations; immutable payouts; receipt waiting/reconcile; Payment/PG/Catalog/DB outages; DLT/original replay",flush=True)
finally:
    pg.stop()
    for app,process,log in processes: process.terminate()
    for app,process,log in processes:
        try: process.wait(timeout=40)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
        log.close()
    subprocess.run(docker+["down","--volumes","--remove-orphans"],cwd=repo,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    (directory/"containers-after.txt").write_text(subprocess.check_output(["docker","ps","--format","{{.ID}} {{.Names}}"],text=True))
