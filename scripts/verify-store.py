#!/usr/bin/env python3
"""Verify Store against completed Auth/Gateway/Studio/Review/Catalog in an isolated, disposable stack."""

from pathlib import Path
from minio_image import ensure_minio_image
import os, socket, subprocess, time, urllib.request, tempfile, json
repo=Path(__file__).resolve().parents[1]
directory=Path(tempfile.mkdtemp(prefix="stove-store-check-"))
project=directory.name
offset=int(os.environ.get("STORE_TEST_PORT_OFFSET","30000"))
apps={"auth":8091,"studio":8085,"gateway":8080,"review":8086,"catalog":8081,"store":8087}
for port in apps.values():
    with socket.socket() as sock: sock.bind(("127.0.0.1",port+offset))
def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        return sock.getsockname()[1]
ports={name:free_port() for name in ["mysql","redis","kafka","minio"]}
minio_image=ensure_minio_image(repo)
subprocess.run(["./gradlew",":apps:auth:bootJar",":apps:gateway:bootJar",":apps:studio:bootJar",":apps:review:bootJar",":apps:catalog:bootJar",":apps:store:bootJar",":e2e:compileTestJava","--offline","--no-daemon"],check=True,cwd=repo)
init=directory/"init.sql"
init.write_text("CREATE DATABASE stove_auth; CREATE DATABASE stove_studio; CREATE DATABASE stove_review; CREATE DATABASE stove_catalog; CREATE DATABASE stove_store; GRANT ALL PRIVILEGES ON stove_auth.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_studio.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_review.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_catalog.* TO 'stove'@'%'; GRANT ALL PRIVILEGES ON stove_store.* TO 'stove'@'%';\n")
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
    def start_app(app,arguments=()):
        port=apps[app]
        jar=next(path for path in (repo/"apps"/app/"build/libs").glob("*.jar") if not path.name.endswith("-plain.jar"))
        log=open(directory/(app+".log"),"a")
        process=subprocess.Popen(["java","-Xmx256m","-jar",str(jar),"--server.port="+str(port+offset),*arguments],env=common,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
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
        if app != "store": start_app(app)
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
    evidence="-Dstove.e2e.store-evidence="+str(directory/"store.json")
    def phase(name):
        verify(":e2e:storeE2eTest", "-Dstove.e2e.store-phase="+name, evidence)
    phase("publishesSearchesAndReplacesOnlyReleasedSnapshots")
    stop_app("store")
    subprocess.run(docker+["exec","-T","mysql","mysql","-ustove","-pstove1234","-e",
        "TRUNCATE TABLE stove_store.store_product;"],check=True)
    start_app("store",["--spring.kafka.listener.auto-startup=false"])
    wait_for(apps_ready,"Store restarted with empty projection and Kafka disabled",180)
    phase("verifiesHttpBootstrapWithKafkaListenerDisabled")
    stop_app("catalog")
    phase("servesStoredSnapshotWithoutCatalog")
    stop_app("store")
    start_app("store",["--spring.kafka.listener.auto-startup=false"])
    wait_for(apps_ready,"Store started without Catalog",180)
    phase("failedBootstrapRemainsUnavailable")
    start_app("catalog")
    wait_for(apps_ready,"Catalog recovered",180)
    stop_app("store")
    start_app("store",["--spring.kafka.listener.auto-startup=false"])
    wait_for(apps_ready,"Store retry after fixing Catalog",180)
    phase("verifiesHttpBootstrapWithKafkaListenerDisabled")
    print("PASS: Store publication, HTTP rebuild, Catalog outage and bootstrap retry (5 phases)",flush=True)
finally:
    for app,process,log in processes: process.terminate()
    for app,process,log in processes:
        try: process.wait(timeout=40)
        except subprocess.TimeoutExpired: process.kill(); process.wait()
        log.close()
    subprocess.run(docker+["down","--volumes","--remove-orphans"],cwd=repo,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
