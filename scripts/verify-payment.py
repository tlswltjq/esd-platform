#!/usr/bin/env python3
"""Verify Payment against completed Auth/Gateway/Studio/Review/Catalog/Store/Order in an isolated, disposable stack."""

from pathlib import Path
import os, socket, subprocess, time, urllib.request, tempfile, json
repo=Path(__file__).resolve().parents[1]
directory=Path(tempfile.mkdtemp(prefix="stove-payment-check-"))
project=directory.name
offset=int(os.environ.get("PAYMENT_TEST_PORT_OFFSET","30000"))
apps={"auth":8091,"studio":8085,"gateway":8080,"review":8086,"catalog":8081,"store":8087,"order":8082,"payment":8083}
for port in apps.values():
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        sock.bind(("127.0.0.1",port+offset))
def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        return sock.getsockname()[1]
ports={name:free_port() for name in ["mysql","redis","kafka","minio"]}
minio_image=os.environ.get("STOVE_TEST_MINIO_IMAGE","stove/minio:RELEASE.2025-04-22T22-12-26Z-local")
if subprocess.run(["docker","image","inspect",minio_image],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
    subprocess.run(["docker","build","-t",minio_image,str(repo/"infra/minio")],check=True)
subprocess.run(["./gradlew",":apps:auth:bootJar",":apps:gateway:bootJar",":apps:studio:bootJar",":apps:review:bootJar",":apps:catalog:bootJar",":apps:store:bootJar",":apps:order:bootJar",":apps:payment:bootJar",":apps:payment:writeVerificationClasspath",":e2e:compileTestJava","--offline","--no-daemon"],check=True,cwd=repo)
init=directory/"init.sql"
init.write_text("CREATE DATABASE stove_auth; CREATE DATABASE stove_studio; CREATE DATABASE stove_review; CREATE DATABASE stove_catalog; CREATE DATABASE stove_store; CREATE DATABASE stove_order; CREATE DATABASE stove_payment; GRANT ALL PRIVILEGES ON stove_auth.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_studio.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_review.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_catalog.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_store.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_order.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_payment.* TO 'stove'@'%';\n")
compose=directory/"compose.yml"
compose.write_text(f"""services:
  mysql:
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
processes=[]
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
    public=f"http://127.0.0.1:{8080+offset}"
    common=os.environ.copy()
    common.update(DB_HOST="127.0.0.1",DB_PORT=str(ports["mysql"]),DB_USER="stove",DB_PASSWORD="stove1234",
        REDIS_HOST="127.0.0.1",REDIS_PORT=str(ports["redis"]),KAFKA_BROKERS="127.0.0.1:"+str(ports["kafka"]),
        AUTH_ISSUER=public,JWT_ISSUER_URI=public,JWT_JWK_SET_URI=f"http://127.0.0.1:{8091+offset}/oauth2/jwks",
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
        jar=next(path for path in (repo/"apps"/app/"build/libs").glob("*.jar") if not path.name.endswith("-plain.jar"))
        log=open(directory/(app+".log"),"a")
        launch=["-jar",str(jar)]
        if fault:
            assert app=="payment"
            classpath=(repo/"apps/payment/build/verification-classpath.txt").read_text()
            launch=["-cp",classpath,"com.stove.payment.PaymentServiceApplication",
                "--spring.main.sources=com.stove.payment.verification.PaymentFaultConfiguration"]
        process=subprocess.Popen(["java","-Xmx256m",*launch,"--server.port="+str(port+offset),*arguments],env=common,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
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
        if app not in {"store", "order", "payment"}: start_app(app)
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
    start_app("payment",["--stove.payment.processing-enabled=false"])
    wait_for(apps_ready,"Order and Payment receipt only",180)
    evidence="-Dstove.e2e.payment-evidence="+str(directory/"payment.json")
    def phase(name):
        verify(":e2e:paymentE2eTest", "-Dstove.e2e.payment-phase="+name, evidence)
        import shutil
        shutil.copytree(repo/"e2e/build/test-results/paymentE2eTest",directory/("junit-"+name),dirs_exist_ok=True)
        print("PASS: "+name,flush=True)
    def sql(query):
        return subprocess.check_output(docker+["exec","-T","mysql","mysql","-ustove","-pstove1234","-N","-e",query],text=True,stderr=subprocess.DEVNULL).strip()
    def check(query,expected,description):
        wait_for(lambda:sql(query)==str(expected),description,90)
    def restart_payment(arguments=(),fault=False):
        stop_app("payment"); start_app("payment",arguments,fault); wait_for(apps_ready,"Payment restarted",180)
    phase("prepareReceiptWithoutProcessing")
    assert sql("SELECT COUNT(*) FROM stove_payment.simulated_payment_charge")=="0"
    restart_payment()
    phase("approvesDurableReceiptAfterRestart")
    restart_payment(["--stove.payment.simulated.result=DECLINED"])
    phase("explicitDeclineUpdatesOrderAndPreservesEarlierApproval")
    restart_payment(["--stove.verification.fail-after-provider=true"],True)
    phase("losesResponseAfterSupplierCommit")
    check("SELECT COUNT(*) FROM stove_payment.purchase_payment WHERE status='PENDING' AND last_error_code='PG_UNAVAILABLE'",1,"Supplier committed; response lost")
    assert sql("SELECT COUNT(*) FROM stove_payment.simulated_payment_charge")=="3"
    assert sql("SELECT COUNT(*) FROM stove_payment.outbox_event")=="2"
    restart_payment()
    phase("recoversLostResponseAfterProcessRestart")
    restart_payment(["--stove.verification.fail-after-outbox=true"],True)
    phase("rollsBackResultAfterOutboxInsertion")
    check("SELECT COUNT(*) FROM stove_payment.purchase_payment WHERE status='PENDING' AND last_error_code='RESULT_UNCONFIRMED'",1,"Result transaction rolled back")
    assert sql("SELECT COUNT(*) FROM stove_payment.simulated_payment_charge")=="4"
    assert sql("SELECT COUNT(*) FROM stove_payment.outbox_event")=="3"
    restart_payment()
    phase("recoversResultTransactionAfterProcessRestart")
    restart_payment(["--stove.payment.processing-enabled=false"])
    phase("preparesReceiptBeforeOrderAndCatalogStop")
    stop_app("order"); stop_app("catalog")
    restart_payment(["--stove.outbox.relay-enabled=false"])
    check("SELECT COUNT(*) FROM stove_payment.purchase_payment WHERE status='PAID'",4,"Supplier result without Order or Catalog")
    check("SELECT COUNT(*) FROM stove_payment.outbox_event WHERE status='SENT'",4,"Previous results sent")
    original=sql("SELECT event_id FROM stove_payment.outbox_event WHERE status='PENDING'")
    assert original
    subprocess.run(docker+["stop","kafka"],check=True)
    restart_payment(["--stove.outbox.relay-enabled=false"])
    phase("paysWithoutOrderOrCatalogAndReadsWhileBrokerDown")
    assert sql("SELECT event_id FROM stove_payment.outbox_event WHERE status='PENDING'")==original
    assert sql("SELECT retry_count FROM stove_payment.outbox_event WHERE status='PENDING'")=="0"
    subprocess.run(docker+["start","kafka"],check=True)
    start_app("order")
    restart_payment()
    phase("catchesUpOriginalResultAndReplaysOriginalEvents")
    check("SELECT COUNT(*) FROM stove_payment.outbox_event WHERE status='SENT'",5,"Original result publication recovered")
    assert sql("SELECT status FROM stove_payment.outbox_event WHERE event_id='"+original+"'")=="SENT"
    # Consume replays before process restart; unchanged row counts verify both inboxes.
    check("SELECT COUNT(*) FROM stove_order.processed_event",5,"Order received five final results")
    stop_app("order"); start_app("order"); restart_payment()
    phase("preservesAllResultsAfterReplayAndRestart")
    for schema,table in [("stove_order","purchase_order"),("stove_order","processed_event"),
            ("stove_payment","purchase_payment"),("stove_payment","processed_event"),
            ("stove_payment","simulated_payment_charge"),("stove_payment","outbox_event")]:
        assert sql("SELECT COUNT(*) FROM "+schema+"."+table)=="5",(schema,table)
    print("PASS: Payment receipt, approval/decline, supplier response loss, result rollback, real restarts, broker/Order/Catalog outage and replay (11 phases)",flush=True)

finally:
    for app,process,log in processes: process.terminate()
    for app,process,log in processes:
        try: process.wait(timeout=40)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
        log.close()
    subprocess.run(docker+["down","--volumes","--remove-orphans"],cwd=repo,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
