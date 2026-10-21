#!/usr/bin/env python3
"""Verify License against completed Auth/Gateway/Studio/Review/Catalog/Store/Order/Payment in an isolated, disposable stack."""

from pathlib import Path
from minio_image import ensure_minio_image
from mock_pg_runtime import MockPgRuntime
import os, socket, subprocess, time, urllib.request, tempfile, json
repo=Path(__file__).resolve().parents[1]
output_root=repo/"runs"
output_root.mkdir(exist_ok=True)
directory=Path(tempfile.mkdtemp(prefix="stove-license-check-",dir=output_root))
project=directory.name
offset=int(os.environ.get("LICENSE_TEST_PORT_OFFSET","31000"))
apps={"auth":8091,"studio":8085,"gateway":8080,"review":8086,"catalog":8081,"store":8087,"order":8082,"payment":8083,"license":8084}
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
subprocess.run(["./gradlew",":simulators:mock-pg:bootJar",":apps:auth:bootJar",":apps:gateway:bootJar",":apps:studio:bootJar",":apps:review:bootJar",":apps:catalog:bootJar",":apps:store:bootJar",":apps:order:bootJar",":apps:payment:bootJar",":apps:license:bootJar",":apps:license:writeVerificationClasspath",":e2e:compileTestJava","--offline","--no-daemon"],check=True,cwd=repo)
init=directory/"init.sql"
init.write_text("CREATE DATABASE stove_auth; CREATE DATABASE stove_studio; CREATE DATABASE stove_review; CREATE DATABASE stove_catalog; CREATE DATABASE stove_store; CREATE DATABASE stove_order; CREATE DATABASE stove_payment; CREATE DATABASE stove_license; GRANT ALL PRIVILEGES ON stove_auth.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_studio.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_review.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_catalog.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_store.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_order.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_payment.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_license.* TO 'stove'@'%';\n")
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
    pg.start()
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
            assert app=="license"
            classpath=(repo/"apps/license/build/verification-classpath.txt").read_text()
            launch=["-cp",classpath,"com.stove.license.LicenseServiceApplication",
                "--spring.main.sources=com.stove.license.verification.LicenseFaultConfiguration"]
        process=subprocess.Popen(["java","-Xmx256m",*launch,"--server.port="+str(port+offset),*arguments],env=pg.client_environment(app,common),cwd=repo,stdout=log,stderr=subprocess.STDOUT)
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
        if app not in {"store", "order", "payment", "license"}: start_app(app)
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
    start_app("license")
    wait_for(apps_ready,"Order, Payment receipt only, License",180)
    evidence="-Dstove.e2e.license-evidence="+str(directory/"license.json")
    def phase(name):
        verify(":e2e:licenseE2eTest", "-Dstove.e2e.license-phase="+name, evidence)
        import shutil
        shutil.copytree(repo/"e2e/build/test-results/licenseE2eTest",directory/("junit-"+name),dirs_exist_ok=True)
        print("PASS: "+name,flush=True)
    def sql(query):
        return subprocess.check_output(docker+["exec","-T","mysql","mysql","-ustove","-pstove1234","-N","-e",query],text=True,stderr=subprocess.DEVNULL).strip()
    def check(query,expected,description):
        wait_for(lambda:sql(query)==str(expected),description,90)
    def restart_payment(arguments=(),fault=False):
        if pg.decision != "APPROVED": pg.start()
        stop_app("payment"); start_app("payment",arguments,fault); wait_for(apps_ready,"Payment restarted",180)
    def restart_license(arguments=(),fault=False):
        stop_app("license"); start_app("license",arguments,fault); wait_for(apps_ready,"License restarted",180)
    def evidence_counts(rights,markers):
        check("SELECT COUNT(*) FROM stove_license.product_license",rights,"Product rights: "+str(rights))
        check("SELECT COUNT(*) FROM stove_license.license_purchase",0,"No writes to historical purchase table")
        check("SELECT COUNT(*) FROM stove_license.processed_event WHERE consumer_group='license-paid-order'",markers,"License Inbox: "+str(markers))
        assert sql("SELECT COUNT(*) FROM stove_license.outbox_event")=="0"
    phase("preparePendingPurchase")
    evidence_counts(0,0)
    restart_payment()
    phase("approvedOrderGrantsOneProduct")
    evidence_counts(1,1)
    first=sql("SELECT CONCAT(license_id,'|',acquired_at) FROM stove_license.product_license ORDER BY id LIMIT 1")
    pg.start("DECLINED")
    phase("declinedOrderDoesNotAddAPurchase")
    evidence_counts(1,1)
    restart_payment()
    phase("rejectsRepurchaseAndRetriesAFailedOrder")
    evidence_counts(2,2)
    phase("anotherBuyerGetsASeparateRight")
    evidence_counts(3,3)
    stop_app("license")
    phase("payWhileLicenseIsStopped")
    check("SELECT COUNT(*) FROM stove_order.purchase_order WHERE status='PAID'",4,"Four paid orders while License stopped")
    evidence_counts(3,3)
    start_app("license"); wait_for(apps_ready,"License catches up",180)
    evidence_counts(4,4)
    phase("catchesUpAfterLicenseRestart")

    marker=directory/"after-commit"
    fault_arguments=["--stove.verification.commit-marker="+str(marker)]
    restart_license([*fault_arguments,"--stove.verification.halt-after-commit=true"],True)
    phase("grantCommitsBeforeTheConsumerProcessExits")
    wait_for(lambda:marker.exists(),"Grant committed; process halted before ACK",90)
    item=next(item for item in processes if item[0]=="license")
    wait_for(lambda:item[1].poll() is not None,"License termination",30)
    assert item[1].returncode==91
    event_id=marker.read_text()
    before=sql("SELECT CONCAT(id,'|',license_id,'|',event_id,'|',acquired_at,'|',received_at) FROM stove_license.product_license WHERE event_id='"+event_id+"'")
    evidence_counts(5,5)
    restart_license(fault_arguments,True)
    wait_for(lambda:Path(str(marker)+".deliveries").read_text().splitlines().count(event_id)>=2,"Original OrderPaid redelivered after unacknowledged commit",90)
    phase("redeliveryAfterUnacknowledgedCommitPreservesTheRight")
    assert sql("SELECT CONCAT(id,'|',license_id,'|',event_id,'|',acquired_at,'|',received_at) FROM stove_license.product_license WHERE event_id='"+event_id+"'")==before
    evidence_counts(5,5)
    restart_license()

    restart_payment(["--stove.payment.processing-enabled=false"])
    phase("preparePaymentBeforeOrderRelayPause")
    stop_app("order"); start_app("order",["--stove.outbox.relay-enabled=false"]); wait_for(apps_ready,"Order relay paused",180)
    restart_payment()
    check("SELECT COUNT(*) FROM stove_order.purchase_order WHERE status='PAID'",6,"Six paid orders")
    check("SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND status='PENDING'",1,"Original OrderPaid stored with relay paused")
    original=sql("SELECT CONCAT(event_id,'|',payload) FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND status='PENDING'")
    evidence_counts(5,5)
    stop_app("catalog")
    subprocess.run(docker+["stop","kafka"],check=True)
    stop_app("order"); start_app("order",["--stove.outbox.relay-enabled=false"]); wait_for(apps_ready,"Order restarted while broker and Catalog stopped",180)
    phase("readsExistingLibraryWhileCatalogAndBrokerAreStopped")
    assert sql("SELECT CONCAT(event_id,'|',payload) FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND status='PENDING'")==original
    subprocess.run(docker+["start","kafka"],check=True)
    stop_app("order"); start_app("order"); wait_for(apps_ready,"Order relay recovered",180)
    evidence_counts(6,6)
    phase("catchesUpOriginalPaidOutboxAndReplaysIt")
    replay=json.loads((directory/"license.json").read_text())
    original_id=original.split("|",1)[0]
    assert replay["replayedEventId"]==original_id
    stored=json.loads(sql("SELECT payload FROM stove_order.outbox_event WHERE event_id='"+original_id+"'"))
    assert stored==replay["replayedEvent"]
    check("SELECT status FROM stove_order.outbox_event WHERE event_id='"+original_id+"'","SENT","Original OrderPaid sent")
    restart_license()
    phase("ownershipSecurityAndStableRightsAfterRestart")
    evidence_counts(6,6)
    assert sql("SELECT CONCAT(license_id,'|',acquired_at) FROM stove_license.product_license ORDER BY id LIMIT 1")==first
    assert pg.sql("SELECT COUNT(*) FROM pg_charge")=="7"
    assert sql("SELECT COUNT(*) FROM stove_payment.simulated_payment_charge")=="0"
    # Seven orders: six approved, one declined. New OrderPaid records never create another payment.
    for table in ["purchase_payment","processed_event","outbox_event"]:
        assert sql("SELECT COUNT(*) FROM stove_payment."+table)=="7",table
    assert sql("SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='OrderPaid'")=="6"
    assert sql("SELECT COUNT(*) FROM stove_license.product_license l "
        "JOIN stove_order.purchase_order o ON l.order_no=o.order_no "
        "JOIN stove_order.outbox_event e ON e.event_type='OrderPaid' AND e.event_id=l.event_id AND e.aggregate_id=o.order_no "
        "WHERE o.status='PAID' AND l.occurred_at=o.updated_at AND l.request_event_id=o.request_event_id "
        "AND l.payment_result_event_id=o.payment_result_event_id AND l.payment_id=o.payment_id "
        "AND l.buyer_subject=o.buyer_subject AND l.product_id=o.product_id AND l.product_code=o.product_code "
        "AND l.title=o.title AND l.content_version=o.content_version AND l.amount=o.amount AND l.currency=o.currency")=="6"

    assert sql("SELECT COUNT(*) FROM stove_license.product_license WHERE order_no=(SELECT order_no FROM stove_order.purchase_order WHERE status='FAILED')")=="0"
    (directory/"summary.json").write_text(json.dumps({"phases":13,"rights":6,"inbox":6,"orders":7,
        "beforeAckEventId":event_id,"replayedEventId":original_id,"firstRight":first},indent=2))
    print("PASS: nine apps; approval/decline/pending; duplicate purchase rejection, failed retry and separate owners; License catch-up; commit-before-ACK halt/redelivery; broker/Catalog outage; Order Outbox restart and original replay (13 phases)",flush=True)

finally:
    pg.stop()
    for app,process,log in processes: process.terminate()
    for app,process,log in processes:
        try: process.wait(timeout=40)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
        log.close()
    subprocess.run(docker+["down","--volumes","--remove-orphans"],cwd=repo,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
