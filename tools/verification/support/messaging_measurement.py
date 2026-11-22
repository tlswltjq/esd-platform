"""격리 스택에서 메시징 비교 실험 한 회차를 돈다(결정 204). ``verify-jib.py --measure``가 부른다.

순서:
1. 부하 드라이버(``:e2e:messagingLoadTest``)가 예열 뒤 측정 주문을 보낸다.
2. 그 주문번호로 업무 완료 시간을 수집한다. 구매 근거가 다 생길 때까지 최대 ``SETTLE_SECONDS`` 기다린다.
3. 같은 주문번호를 순서 키로 가진 이벤트만 골라 브로커 구간 지연을 수집한다. 건수가 멈출 때까지 기다린다.
4. 결과를 회차 폴더에 남긴다.

업무 데이터는 수집 단계에서 읽기만 한다. 수치는 회차 유효성(``validity``)과 함께 돌려준다. 무효 회차는
``verify-jib.py``가 실패로 끝낸다.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

from support import resource_sampling

SETTLE_SECONDS = 120
# 예정 시각보다 이만큼 늦게 출발한 요청이 있으면 도착률을 지키지 못한 회차다.
LATE_START_MS = 100.0
# 결제 성공 주문 하나마다 한 번씩 처리해야 하는 소비 그룹(구매 여정, 2026-11-16 실측 기준).
PURCHASE_GROUPS = ["payment-order-request", "order-payment-result", "license-paid-order",
                   "order-license-grant", "settlement-paid-order"]


# 메시징을 쓰는 앱(Outbox 발행 또는 @EventSubscriber 소비). Store 는 DB 가 없어 항상 Kafka 로 받는다.
MESSAGING_APPS = {"auth", "studio", "review", "catalog", "order", "payment", "license", "settlement"}
BROKERS = ("kafka", "direct", "rabbitmq", "redis-streams")
# 비교하는 브로커가 모두 같은 병렬도를 갖게 한다: Kafka 토픽 파티션 수 = RabbitMQ (그룹, 토픽)당 큐 수.
# docker-compose.yml 의 KAFKA_NUM_PARTITIONS 와 같은 값이다.
PARALLELISM = 3
RABBIT_USER = "stove"
RABBIT_PASSWORD = "stove1234"
# RabbitMQ 로 받는 @EventSubscriber 가 없는 토픽(발행 앱 → 토픽). auth 는 아무도 받지 않고, catalog 는 Store 가 Kafka 로만 받는다.
RABBIT_OPTIONAL_TOPICS = {"auth": "stove.auth.v1", "catalog": "stove.catalog.v1"}


def broker_environment(app, broker):
    """결정 203 비교 대상 adapter 를 고르는 앱 환경 변수. kafka 는 기본값이라 아무것도 넣지 않는다."""
    if broker not in BROKERS:
        raise ValueError("알 수 없는 broker: " + broker)
    if broker == "kafka" or app not in MESSAGING_APPS:
        return {}
    environment = {"STOVE_MESSAGING_BROKER": broker}
    if broker == "direct" and app == "catalog":
        # Store 는 Outbox 를 직접 읽을 수 없어(DB 없음) catalog 토픽만 Kafka 로도 보낸다. 구매 여정 밖이라 측정에 섞이지 않는다.
        environment["STOVE_MESSAGING_DIRECT_ALSOKAFKATOPICS"] = "stove.catalog.v1"
    if broker == "rabbitmq":
        environment.update(SPRING_RABBITMQ_HOST="rabbitmq", SPRING_RABBITMQ_PORT="5672",
                           SPRING_RABBITMQ_USERNAME=RABBIT_USER, SPRING_RABBITMQ_PASSWORD=RABBIT_PASSWORD,
                           STOVE_MESSAGING_RABBITMQ_QUEUESPERTOPIC=str(PARALLELISM))
        if app in RABBIT_OPTIONAL_TOPICS:
            # 이 토픽은 RabbitMQ 로 받는 소비자가 없다. 받을 큐가 없어 되돌아와도 발행 성공으로 친다(PR #211 R3).
            environment["STOVE_MESSAGING_RABBITMQ_OPTIONALTOPICS"] = RABBIT_OPTIONAL_TOPICS[app]
        if app == "catalog":
            # Store 는 격리 보관 DB 가 없어 Kafka(DLT)로만 받는다. 위와 같은 이유로 catalog 토픽만 Kafka 로도 보낸다.
            environment["STOVE_MESSAGING_RABBITMQ_ALSOKAFKATOPICS"] = "stove.catalog.v1"
    if broker == "redis-streams":
        environment.update(STOVE_MESSAGING_REDISSTREAMS_URI="redis://streams:6379",
                           STOVE_MESSAGING_REDISSTREAMS_STREAMSPERTOPIC=str(PARALLELISM))
        if app == "catalog":
            # 위와 같은 이유(Store 는 Kafka 로만 받는다)로 catalog 토픽만 Kafka 로도 보낸다.
            environment["STOVE_MESSAGING_REDISSTREAMS_ALSOKAFKATOPICS"] = "stove.catalog.v1"
    return environment


def extra_environment(assignments):
    """``--app-env NAME=VALUE`` 목록을 앱 환경으로. 형식이 틀리거나 브로커 선택 변수면 거절한다(브로커는 ``--broker`` 로만)."""
    environment = {}
    for assignment in assignments:
        key, separator, value = assignment.partition("=")
        if not separator or not key:
            raise SystemExit("--app-env needs NAME=VALUE: " + assignment)
        if key == "STOVE_MESSAGING_BROKER":
            raise SystemExit("--app-env cannot set STOVE_MESSAGING_BROKER; use --broker")
        environment[key] = value
    return environment


def infra_services(broker):
    """그 브로커 회차에만 더 띄울 기반 서비스(Compose 서비스 정의). Kafka 는 Store 때문에 언제나 뜬다."""
    if broker == "redis-streams":
        # 게이트웨이 세션용 redis 와 따로 둔다. AOF(초마다 fsync)를 켜 Kafka 의 디스크 로그와 비슷한 내구성으로 맞춘다.
        return {"streams": {"image": "redis:7-alpine", "networks": ["app"],
                            "command": ["redis-server", "--appendonly", "yes", "--appendfsync", "everysec"],
                            "healthcheck": {"test": ["CMD", "redis-cli", "ping"], "interval": "2s", "timeout": "3s",
                                            "retries": 60}}}
    if broker != "rabbitmq":
        return {}
    return {"rabbitmq": {"image": "rabbitmq:4.1-management-alpine", "networks": ["app"],
                         "environment": {"RABBITMQ_DEFAULT_USER": RABBIT_USER, "RABBITMQ_DEFAULT_PASS": RABBIT_PASSWORD},
                         "healthcheck": {"test": ["CMD", "rabbitmq-diagnostics", "-q", "check_port_connectivity"],
                                         "interval": "3s", "timeout": "10s", "retries": 60}}}


def load_command(offset, rate, seconds, buyers, evidence, warmup=10, refund_every=0):
    return ["./gradlew", ":e2e:messagingLoadTest", "--no-daemon", "--console=plain",
            "-Dstove.e2e.load-refund-every=" + str(refund_every),
            "-Dstove.e2e.port-offset=" + str(offset),
            "-Dstove.e2e.load-rate=" + str(rate),
            "-Dstove.e2e.load-duration-secs=" + str(seconds),
            "-Dstove.e2e.load-warmup-secs=" + str(warmup),
            "-Dstove.e2e.load-buyers=" + str(buyers),
            "-Dstove.e2e.load-evidence=" + str(evidence)]


def journey_command(root, container, evidence, out):
    return [sys.executable, "-B", str(Path(root) / "tools/measurement/journey_latency.py"),
            "--container", container, "--evidence", str(evidence), "--wait-secs", str(SETTLE_SECONDS),
            "--out", str(out)]


def broker_command(root, container, evidence, out):
    return [sys.executable, "-B", str(Path(root) / "tools/measurement/broker_latency.py"),
            "--container", container, "--keys-from", str(evidence), "--settle-secs", str(SETTLE_SECONDS),
            "--out", str(out)]


def _nearest_rank(values, p):
    ordered = sorted(values)
    return ordered[max(0, -(-p * len(ordered) // 100) - 1)] if ordered else None


def refund_problems(measured):
    """환불을 요청한 측정 주문 가운데 요청이 거절됐거나 COMPLETED 에 닿지 않은 건."""
    requested = [order for order in measured if "refundStatus" in order]
    rejected = sum(1 for order in requested if order["refundStatus"] not in (200, 202))
    unfinished = sum(1 for order in requested if order.get("refundState") != "COMPLETED")
    return len(requested), rejected, unfinished


def validity(measured, journey, broker, order=None):
    """회차를 결론에 써도 되는지와 그 이유. 실패·미완료·도착률 미달·소비 누락·같은 키 순서 위반·환불 요청 거절이 하나라도 있으면 무효.

    순서 위반(결정 204 F)은 합격 조건이라, 위반이 있으면 그 회차 수치는 비교에 쓰지 않는다.
    """
    reasons = []
    http_failures = sum(1 for order in measured if order.get("status") != 200)
    late = sum(1 for order in measured if order.get("lateMs", 0.0) > LATE_START_MS)
    if http_failures:
        reasons.append("HTTP 실패 %d건" % http_failures)
    if late:
        reasons.append("예정보다 %.0fms 넘게 늦게 출발한 요청 %d건" % (LATE_START_MS, late))
    if journey.get("failed"):
        reasons.append("결제 실패 주문 %d건" % journey["failed"])
    if journey.get("incomplete"):
        reasons.append("구매 근거 미완료 %d건" % journey["incomplete"])
    paid = journey.get("completed", 0)
    for group in PURCHASE_GROUPS:
        values = broker.get(group, {})
        consumed = values.get("count", 0) + values.get("negative", 0)
        if consumed != paid:
            reasons.append("%s 소비 %d건(기대 %d)" % (group, consumed, paid))
        if values.get("negative"):
            reasons.append("%s 음수 구간 %d건" % (group, values["negative"]))
    for group, checked in (order or {}).items():
        if checked.get("violations"):
            reasons.append("%s 같은 키 순서 위반 %d건" % (group, checked["violations"]))
    # 환불 요청 거절만 무효로 본다. 격리 스택(모의 PG, SIMULATED)에는 환불 취소 구현이 없고 환불 실행이 꺼져 있어
    # 환불은 PG 결과 대기(PENDING_PAYMENT_REFUND)에서 멈춘다 — 미완료는 진단(refund_diagnosis)으로만 남긴다.
    requested, rejected, _unfinished = refund_problems(measured)
    if rejected:
        reasons.append("환불 요청 거절 %d건(요청 %d)" % (rejected, requested))
    return {"valid": not reasons, "reasons": reasons, "http_failures": http_failures, "late_starts": late}


# 업무 DB(모든 모듈 스키마가 한 MySQL 서버)가 받은 조회 수. 브로커 없음은 소비자가 이 서버를 폴링한다.
DB_LOAD_SQL = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_select', 'Questions');"
IDLE_SECONDS = 10


def db_counters(capture, container, password):
    """업무 MySQL 의 누적 조회 수(``Com_select``)와 전체 문장 수(``Questions``), 그리고 읽은 시각.

    ``Com_select`` 는 서버 전체 카운터다 — 모든 앱의 백그라운드 조회(스케줄러·릴레이 폴링 등)와 이 조회 자체가 섞인다.
    시각은 조회 전후의 가운데(``time.monotonic``)로 잡아 ``docker exec`` 지연이 분모에 덜 섞이게 한다(PR #214 R2).
    ``password`` 는 격리 DB를 만들 때 넣은 값이다 — 호스트 환경 변수를 따르지 않는다(R1).
    """
    started = time.monotonic()
    text = capture(["docker", "exec", "-i", container, "mysql", "-uroot", "-p" + password, "-N", "-B"],
                   input=DB_LOAD_SQL, stderr=subprocess.DEVNULL)
    taken_at = (started + time.monotonic()) / 2
    counters = {}
    for line in text.splitlines():
        name, _, value = line.partition("\t")
        counters[name.strip()] = int(value.strip())
    return counters, taken_at


def db_rate(before, after):
    """두 읽기(``db_counters`` 결과) 사이 실제 경과 시간으로 나눈 초당 조회 수·문장 수."""
    (start, started_at), (end, ended_at) = before, after
    seconds = ended_at - started_at
    rates = {name: round((end[name] - start[name]) / seconds, 1) for name in ("Com_select", "Questions")}
    rates["seconds"] = round(seconds, 2)
    return rates


# 환불이 끝나지 않았을 때 어디서 멈췄는지 보는 읽기 전용 조회(상태별 건수, 환불 관련 Outbox 이벤트, 격리 보관).
REFUND_DIAGNOSIS_SQL = " UNION ALL ".join([
    "SELECT 'order_refund', status, COUNT(*) FROM stove_order.order_refund GROUP BY status",
    "SELECT 'license_suspension', '-', COUNT(*) FROM stove_license.license_suspension",
    "SELECT 'refund_execution', status, COUNT(*) FROM stove_payment.refund_execution GROUP BY status",
] + ["SELECT 'outbox %s', CONCAT(event_type, '/', status), COUNT(*) FROM stove_%s.outbox_event"
     " WHERE event_type LIKE '%%efund%%' OR event_type LIKE '%%uspen%%' GROUP BY event_type, status" % (app, app)
     for app in ("order", "license", "payment")]
  + ["SELECT 'quarantine %s', CONCAT(consumer_group, '/', status), COUNT(*) FROM stove_%s.consumer_quarantine"
     " GROUP BY consumer_group, status" % (app, app) for app in ("order", "license", "payment")]) + ";"


def refund_diagnosis(capture, container, password):
    """환불 흐름 표의 상태별 건수. 읽지 못하면 오류 문구를 남긴다(측정을 멈추지 않는다)."""
    try:
        text = capture(["docker", "exec", "-i", container, "mysql", "-uroot", "-p" + password, "-N", "-B"],
                       input=REFUND_DIAGNOSIS_SQL, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as failure:
        return {"error": (failure.output or "")[-500:]}
    rows = {}
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            rows.setdefault(parts[0], {})[parts[1]] = int(parts[2])
    return rows


DUPLICATES_METRIC = "stove_inbox_duplicates_total"


def inbox_duplicates(text):
    """Prometheus 본문에서 소비 그룹별 재전달 차단 수(Inbox 가 이미 처리한 이벤트를 다시 받아 건너뛴 횟수)."""
    totals = {}
    for line in text.splitlines():
        if not line.startswith(DUPLICATES_METRIC + "{"):
            continue
        labels, _, value = line.rpartition(" ")
        group = labels.split('consumer_group="', 1)[1].split('"', 1)[0] if 'consumer_group="' in labels else "unknown"
        totals[group] = totals.get(group, 0) + int(float(value))
    return totals


def redeliveries(fetch, urls):
    """앱마다 ``/actuator/prometheus`` 를 읽어 재전달 차단 수를 합친다. 읽지 못한 앱은 ``unreadable`` 에 남긴다(0 으로 적지 않는다)."""
    totals = {}
    unreadable = []
    for url in urls:
        try:
            for group, count in inbox_duplicates(fetch(url)).items():
                totals[group] = totals.get(group, 0) + count
        except Exception:  # noqa: BLE001 — 측정 보조값이라 회차를 멈추지 않고 읽지 못했다고 남긴다
            unreadable.append(url)
    return {"by_group": dict(sorted(totals.items())), "unreadable": unreadable}


def measure(root, docker, run, capture, output, offset, rate, seconds, buyers, warmup=10, db_password="root1234",
            metrics_urls=(), fetch=None, refund_every=0, broker_name="kafka"):
    """한 회차를 돌고 요약을 돌려준다. ``run``·``capture``는 ``verify-jib.py``의 것을 받는다.

    부하 전 ``IDLE_SECONDS`` 동안의 업무 DB 초당 조회 수(유휴 비용)와 부하 드라이버 실행 구간(기동·예열 포함)의
    초당 조회 수를 같이 남긴다. 같은 두 구간의 브로커·업무 DB 컨테이너 CPU·메모리와 회차 전후 디스크도 남긴다(결정 210 S6).
    """
    load = Path(output) / "load.json"
    container = capture(docker + ["ps", "-q", "mysql"])
    containers = {service: capture(docker + ["ps", "-q", service])
                  for service in resource_sampling.sampled_services(broker_name)}
    disk_before, disk_errors_before = resource_sampling.disk_kib(capture, containers)
    idle_sampler = resource_sampling.Sampler(capture, containers).start()
    try:
        idle_start = db_counters(capture, container, db_password)
        time.sleep(IDLE_SECONDS)
        idle_end = db_counters(capture, container, db_password)
    finally:
        # 유휴 읽기가 실패해도 표본 스레드가 계속 docker 를 부르지 않게 한다(PR #221 R3).
        idle_samples = idle_sampler.stop()
    load_sampler = resource_sampling.Sampler(capture, containers).start()
    try:
        run(load_command(offset, rate, seconds, buyers, load, warmup, refund_every), "measure-load")
        # 부하가 끝난 바로 그때 읽는다. 표본 스레드 종료 대기(최대 표본 간격)가 부하 구간에 섞이지 않게(R4).
        load_end = db_counters(capture, container, db_password)
    finally:
        load_samples = load_sampler.stop()
    # 디스크도 부하 직후에 읽는다. 아래 수집 단계(읽기 전용 조회)가 증가량에 섞이지 않게(R6).
    disk_after, disk_errors_after = resource_sampling.disk_kib(capture, containers)
    diagnosis = refund_diagnosis(capture, container, db_password) if refund_every else None
    if diagnosis is not None:
        print("REFUND DIAGNOSIS " + json.dumps(diagnosis, ensure_ascii=False), flush=True)
    db_load = {"idle_per_second": db_rate(idle_start, idle_end), "load_run_per_second": db_rate(idle_end, load_end)}
    journey_file = Path(output) / "journey.json"
    run(journey_command(root, container, load, journey_file), "measure-journey")
    broker_file = Path(output) / "broker.json"
    run(broker_command(root, container, load, broker_file), "measure-broker")
    resources = {"broker_service": resource_sampling.broker_service(broker_name),
                 "interval_seconds": resource_sampling.INTERVAL_SECONDS,
                 "idle": resource_sampling.summarize(idle_samples),
                 "load_run": resource_sampling.summarize(load_samples),
                 "stats_failures": idle_sampler.failures + load_sampler.failures,
                 "stats_partial": idle_sampler.partial + load_sampler.partial,
                 "disk": resource_sampling.disk_growth(disk_before, disk_after),
                 "disk_errors": {"before": disk_errors_before, "after": disk_errors_after}}
    loaded = json.loads(load.read_text())
    measured = [order for order in loaded["orders"] if not order.get("warmup")]
    journey = json.loads(journey_file.read_text())["purchase"]
    collected = json.loads(broker_file.read_text())
    broker = collected["groups"]
    order = collected.get("order", {})
    http = [order["httpMs"] for order in measured if "httpMs" in order]
    late = [order["lateMs"] for order in measured if "lateMs" in order]
    return {"rate": rate, "duration_seconds": seconds, "warmup_seconds": warmup, "buyers": buyers,
            "planned": len(measured), "accepted": sum(1 for order in measured if order.get("status") == 200),
            "http_p95_ms": _nearest_rank(http, 95), "late_start_max_ms": max(late) if late else None,
            "journey": journey, "broker": broker, "order": order,
            "refunds": dict(zip(("requested", "rejected", "unfinished"), refund_problems(measured))),
            "refund_diagnosis": diagnosis,
            "redeliveries": redeliveries(fetch, metrics_urls) if fetch else None,
            "db_load": db_load, "resources": resources, "validity": validity(measured, journey, broker, order)}
