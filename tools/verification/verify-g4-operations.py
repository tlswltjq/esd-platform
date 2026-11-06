#!/usr/bin/env python3
"""G4 operational journey. Real nine apps; Payment is an explicit Kafka contract fixture, never a PG call."""

from pathlib import Path
from support.ports import application_offset
from support.boot_jars import application_jar
from support.minio_image import ensure_minio_image
import os, socket, subprocess, time, urllib.request, tempfile, json, shutil, secrets
import xml.etree.ElementTree as ET
repo=Path(__file__).resolve().parents[2]
directory=Path(tempfile.mkdtemp(prefix="stove-g4-operations-"))
project=directory.name
apps={"auth":8091,"studio":8085,"gateway":8080,"review":8086,"catalog":8081,
      "store":8087,"order":8082,"license":8084,"download":8088}
offset=application_offset("verify-g4-operations","G4_TEST_PORT_OFFSET",apps.values())
ephemeral_file=Path("/proc/sys/net/ipv4/ip_local_port_range")
if ephemeral_file.exists():
    ephemeral_start,ephemeral_end=map(int,ephemeral_file.read_text().split())
    if any(ephemeral_start<=port+offset<=ephemeral_end for port in apps.values()):
        raise RuntimeError("Application ports overlap the host ephemeral range; choose another G4_TEST_PORT_OFFSET")
allocated_ports=set()
container_ids=subprocess.check_output(["docker","ps","-q"],text=True).split()
if container_ids:
    mappings=subprocess.check_output(["docker","inspect","--format","{{json .NetworkSettings.Ports}}",*container_ids],text=True)
    for line in mappings.splitlines():
        for bindings in json.loads(line).values():
            for binding in bindings or []: allocated_ports.add(int(binding["HostPort"]))
for port in apps.values():
    if port+offset in allocated_ports: raise RuntimeError("Application port is already allocated by Docker")
    allocated_ports.add(port+offset)
def free_port():
    while True:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1",0))
            port=sock.getsockname()[1]
        if port not in allocated_ports:
            allocated_ports.add(port)
            return port
ports={name:free_port() for name in ["mysql","redis","kafka","minio"]}
minio_image=ensure_minio_image(repo)
subprocess.run(["./gradlew",*[":apps:"+app+":bootJar" for app in apps],":e2e:compileTestJava",
                "--offline","--no-daemon","--max-workers=2","--console=plain"],check=True,cwd=repo)
init=directory/"init.sql"
databases=["auth","studio","review","catalog","store","order","license"]
init.write_text("\n".join("CREATE DATABASE stove_"+db+"; GRANT ALL PRIVILEGES ON stove_"+db+".* TO 'stove'@'%';" for db in databases))
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
    wait_for(lambda:subprocess.run(docker+["exec","-T","mysql","mysql","--protocol=TCP","-h","127.0.0.1",
            "-ustove","-pstove1234","stove_studio","-e","SELECT 1"],
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0,"MySQL application database and credentials")
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
    for user,policy_name,policy_file in [
            ("download-reader","g4-download-reader","download-reader.json"),
            ("catalog-tagger","g4-catalog-tagger","catalog-download-tagger.json")]:
        policy=(repo/"infra/minio/policies"/policy_file).read_text()
        target="/tmp/"+policy_file
        subprocess.run(docker+["exec","-T","minio","sh","-c","cat > "+target],input=policy,text=True,check=True)
        subprocess.run(mc+["admin","policy","create","admin",policy_name,target],check=True,stdout=subprocess.DEVNULL)
        subprocess.run(mc+["admin","user","add","admin",user,"g4-isolated-storage-password"],check=True,stdout=subprocess.DEVNULL)
        subprocess.run(mc+["admin","policy","attach","admin",policy_name,"--user",user],check=True,stdout=subprocess.DEVNULL)
        subprocess.run(mc+["alias","set",user,"http://localhost:9000",user,"g4-isolated-storage-password"],check=True,stdout=subprocess.DEVNULL)
        denied=subprocess.run(mc+["pipe",user+"/stove-builds/write-must-fail.zip"],input=b"forbidden",stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if denied.returncode==0: raise RuntimeError(user+" unexpectedly has content write access")
        denied=subprocess.run(mc+["rm",user+"/stove-builds/iam-check.bin"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if denied.returncode==0: raise RuntimeError(user+" unexpectedly has delete access")
    subprocess.run(mc+["cat","download-reader/stove-builds/iam-check.bin"],check=True,stdout=subprocess.DEVNULL)
    denied=subprocess.run(mc+["tag","set","download-reader/stove-builds/iam-check.bin","stove-download-blocked=true"],
                          stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if denied.returncode==0: raise RuntimeError("Download reader unexpectedly has tagging access")
    denied=subprocess.run(mc+["cat","catalog-tagger/stove-builds/iam-check.bin"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if denied.returncode==0: raise RuntimeError("Catalog tagger unexpectedly has content read access")
    print("PASS: separate content reader and tag-only Catalog account",flush=True)
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
    keys={name:secrets.token_urlsafe(32) for name in
          ["CATALOG_STORE_SERVICE_KEY","CATALOG_ORDER_SERVICE_KEY","CATALOG_DOWNLOAD_SERVICE_KEY","PAYMENT_ORDER_SERVICE_KEY"]}
    secret_file=directory/"secrets.json"
    secret_file.touch(mode=0o600)
    secret_file.write_text(json.dumps({"paymentOrderServiceKey":keys["PAYMENT_ORDER_SERVICE_KEY"]}))
    common.update(keys)
    common.update(STORE_SYNC_RECONCILE_INTERVAL="PT2S", STORE_SYNC_RETRY_DELAY="PT1S", STORE_SYNC_POLL_INTERVAL_MS="100",
                  CATALOG_BASE_URL=f"http://127.0.0.1:{8081+offset}", LICENSE_BASE_URL=f"http://127.0.0.1:{8084+offset}",
                  STORAGE_BUCKET="stove-builds",DOWNLOAD_BUILD_URL_TTL="PT15M",
                  DOWNLOAD_STORAGE_ACCESS_KEY="download-reader",DOWNLOAD_STORAGE_SECRET_KEY="g4-isolated-storage-password",
                  CATALOG_DOWNLOAD_STORAGE_ENABLED="true",CATALOG_DOWNLOAD_STORAGE_ENDPOINT=f"http://127.0.0.1:{ports['minio']}",
                  CATALOG_DOWNLOAD_STORAGE_BUCKET="stove-builds",CATALOG_DOWNLOAD_STORAGE_ACCESS_KEY="catalog-tagger",
                  CATALOG_DOWNLOAD_STORAGE_SECRET_KEY="g4-isolated-storage-password")
    for app,port in apps.items(): common[app.upper()+"_URI"]=f"http://127.0.0.1:{port+offset}"
    def start_app(app,arguments=()):
        port=apps[app]
        jar=application_jar(repo,app)
        log=open(directory/(app+".log"),"a")
        process=subprocess.Popen(["java","-Xmx256m","-jar",str(jar),"--server.address=127.0.0.1","--server.port="+str(port+offset),*arguments],env=common,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
        processes.append((app,process,log))
    def stop_app(name):
        item=next(item for item in processes if item[0]==name)
        app,process,log=item
        process.terminate()
        try: process.wait(timeout=40)
        except subprocess.TimeoutExpired: process.kill();process.wait()
        log.close();processes.remove(item)
    def apps_ready():
        for app,process,log in processes:
            if process.poll() is not None: raise RuntimeError(app+" stopped; inspect isolated app log")
        return all(ready(f"http://127.0.0.1:{apps[app]+offset}/actuator/health") for app,process,log in processes)
    # Eight concurrent JVM cold starts can exceed the applications' short initial DB login deadline.
    # This journey verifies business boundaries, so bound startup contention instead of retrying failed checks.
    for batch in (("auth", "gateway"), ("studio", "review"), ("catalog", "order"), ("license", "download")):
        for app in batch: start_app(app)
        wait_for(apps_ready,"/".join(batch),180)
    start_app("store")
    wait_for(apps_ready,"Store",180)
    phases=[]
    def phase(name):
        started=time.monotonic()
        command=["./gradlew",":e2e:g4OperationsE2eTest","-Dstove.e2e.port-offset="+str(offset),
                 "-Dstove.e2e.kafka-brokers=127.0.0.1:"+str(ports["kafka"]),"-Dstove.e2e.g4-phase="+name,
                 "-Dstove.e2e.g4-evidence="+str(directory/"private.json"),"-Dstove.e2e.g4-secrets="+str(secret_file),
                 "--offline","--no-daemon","--max-workers=2","--console=plain"]
        result=subprocess.run(command,cwd=repo)
        output=repo/"e2e/build/test-results/g4OperationsE2eTest"
        destination=directory/"checks"/name
        if output.exists(): shutil.copytree(output,destination,dirs_exist_ok=True)
        suites=[ET.parse(path).getroot() for path in destination.glob("TEST-*.xml")]
        counts={key:sum(int(suite.attrib.get(key,0)) for suite in suites) for key in ["tests","failures","errors","skipped"]}
        record={"phase":name,"exit":result.returncode,"seconds":round(time.monotonic()-started,2),**counts}
        phases.append(record)
        (directory/"phases.json").write_text(json.dumps(phases,indent=2))
        if result.returncode: raise RuntimeError("G4 phase failed: "+name)
        if counts!={"tests":1,"failures":0,"errors":0,"skipped":0}: raise RuntimeError("Invalid executed check count")
    def projection(label):
        state=json.loads((directory/"private.json").read_text())
        # Generated UUID product codes only; assert the identifier before placing it in SQL.
        code=state["code"]
        if not all(char.isalnum() or char=='-' for char in code): raise RuntimeError("Invalid fixture code")
        query="SELECT product_code,listed,catalog_version,content_version,build_version FROM stove_store.store_product ORDER BY product_code"
        result=subprocess.run(docker+["exec","-T","mysql","mysql","-N","-ustove","-pstove1234","-e",query],capture_output=True,text=True,check=True)
        rows=[line.split("\t") for line in result.stdout.splitlines()]
        hidden=[row for row in rows if row[0]==code]
        if len(rows)!=2 or len(hidden)!=1 or hidden[0][1]!='0' or hidden[0][4]!='3':
            raise RuntimeError("HTTP bootstrap lost hidden product or current build")
        (directory/(label+"-projection.json")).write_text(json.dumps(rows,indent=2))
    phase("publishesAndPreparesOwnedAndPendingOrders")
    phase("genreOnlyUpdateRequiresApprovalAndAndSearchMatchesAll")
    stop_app("catalog")
    start_app("catalog",["--stove.catalog.publication.worker-enabled=false"])
    wait_for(apps_ready,"Catalog with paused publication worker",180)
    phase("withdrawsAnUpdateAndQueuesApprovedReplacement")
    stop_app("catalog")
    start_app("catalog")
    wait_for(apps_ready,"Catalog with automatic publication restored",180)
    phase("verifiesPublishedUpdateAndPermanentRight")
    phase("enforcesIndependentSalesAndListingControls")
    phase("restrictsHistoricalUrlsAndNewlyPublishedBuildIndependently")
    stop_app("store")
    subprocess.run(docker+["exec","-T","mysql","mysql","-ustove","-pstove1234","-e","DELETE FROM stove_store.store_product"],check=True,stdout=subprocess.DEVNULL)
    start_app("store",["--spring.kafka.listener.auto-startup=false"])
    wait_for(apps_ready,"Store rebuilding empty projection with Kafka disabled",180)
    phase("hiddenProjectionSurvivesHttpBootstrap")
    projection("rebuilt")
    state=json.loads((directory/"private.json").read_text())
    main_code=state["code"]
    control_code=state["controlCode"]
    if not all(code and all(char.isalnum() or char=='-' for char in code) for code in (main_code,control_code)):
        raise RuntimeError("Invalid generated fixture code")
    def sql(query):
        result=subprocess.run(docker+["exec","-T","mysql","mysql","-N","-B","--raw","-ustove","-pstove1234","-e",query],
                              capture_output=True,text=True,check=True)
        return [line.split("\t") for line in result.stdout.splitlines()]
    audit_start=int(sql("SELECT COALESCE(MAX(id),0) FROM stove_store.store_reconciliation")[0][0])
    damaged=sql("START TRANSACTION; DELETE FROM stove_store.store_product WHERE product_code='"+control_code+
                "'; UPDATE stove_store.store_product SET price=1,listed=TRUE,title='Injected projection mismatch' WHERE product_code='"+main_code+
                "'; DELETE FROM stove_store.store_product_genre WHERE product_id=(SELECT product_id FROM stove_store.store_product WHERE product_code='"+main_code+
                "'); INSERT INTO stove_store.store_product_genre SELECT product_id,'PUZZLE' FROM stove_store.store_product WHERE product_code='"+main_code+
                "'; SELECT catalog_version,price,listed FROM stove_store.store_product WHERE product_code='"+main_code+"'; COMMIT;")
    if len(damaged)!=1 or damaged[0][1:]!=['1','1']: raise RuntimeError("Projection mismatch injection failed")
    phase("automaticReconciliationRepairsMissingAndSameVersionProjection")
    corrected=sql("SELECT catalog_version,price,listed FROM stove_store.store_product WHERE product_code='"+main_code+"'")
    repairs=sql("SELECT action,product_code,JSON_UNQUOTE(JSON_EXTRACT(before_snapshot,'$.price')),"
                "JSON_UNQUOTE(JSON_EXTRACT(after_snapshot,'$.price')),catalog_version,"
                "JSON_EXTRACT(before_snapshot,'$.genres'),JSON_EXTRACT(after_snapshot,'$.genres') FROM stove_store.store_reconciliation "
                "WHERE id>"+str(audit_start)+" ORDER BY id")
    if corrected!=[[damaged[0][0],'2500','0']]: raise RuntimeError("Same-version canonical repair failed")
    if not any(row[0]=='REPAIRED' and row[1]==main_code and row[2:4]==['1','2500'] and row[4]==damaged[0][0] for row in repairs):
        raise RuntimeError("Atomic repair evidence missing")
    if not any(row[0]=='REPAIRED' and row[1]==main_code and json.loads(row[5])==['PUZZLE'] and json.loads(row[6])==['ACTION','RPG'] for row in repairs):
        raise RuntimeError("Atomic genre repair evidence missing")
    if not any(row[0]=='INSERTED' and row[1]==control_code for row in repairs): raise RuntimeError("Automatic missing projection repair missing")
    (directory/"automatic-reconciliation.json").write_text(json.dumps({"before":damaged,"after":corrected,"audit":repairs},indent=2))
    stop_app("catalog")
    phase("servesCachedSnapshotDuringCatalogOutage")
    stop_app("store")
    start_app("store",["--spring.kafka.listener.auto-startup=false"])
    wait_for(apps_ready,"Store started during Catalog outage",180)
    phase("failedBootstrapIsUnavailable")
    recovering_store=next(process.pid for app,process,log in processes if app=="store")
    start_app("catalog")
    wait_for(apps_ready,"Catalog recovered",180)
    phase("bootstrapRetryRecoversBothVisibilityStates")
    recovered_store=next(process.pid for app,process,log in processes if app=="store")
    if recovering_store!=recovered_store: raise RuntimeError("Store was restarted during automatic recovery")
    (directory/"automatic-recovery.json").write_text(json.dumps({"storePidBefore":recovering_store,"storePidAfter":recovered_store,"restarted":False},indent=2))
    projection("recovered")
    stop_app("store")
    start_app("store")
    wait_for(apps_ready,"Store Kafka delivery restored",180)
    phase("restoresPublicAccessAndCurrentPurchaseSnapshot")
    state=json.loads((directory/"private.json").read_text())
    safe={key:state[key] for key in ["project","controlProject","code","controlCode","paidOrder","sellerOrder","adminOrder", "paymentEvidence","withdrawnRelease","survivingRelease","latestPendingOrder","finalProduct"]}
    safe["licenseUnchanged"]=True
    (directory/"journey-summary.json").write_text(json.dumps(safe,indent=2))
    print("PASS: G4 nine-app operations, 12 phases including genre approval/AND search and automatic repair/recovery; synthetic payment result, no PG calls",flush=True)
finally:
    for app,process,log in processes: process.terminate()
    for app,process,log in processes:
        try: process.wait(timeout=40)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
        log.close()
    subprocess.run(docker+["down","--volumes","--remove-orphans"],cwd=repo,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
