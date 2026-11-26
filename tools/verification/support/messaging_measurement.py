"""격리 스택에서 Kafka 메시징 측정 한 회차를 돈다(결정 204·227). ``verify-jib.py --measure``가 부른다.

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
import threading
import time
from pathlib import Path

from support import resource_sampling

SETTLE_SECONDS = 120
# 결정 231: 복구 관찰 제한시간은 부하 종료 뒤 10분. 업무 완료·Outstanding 시계열은 이만큼까지 기다린다.
OBSERVATION_SECONDS = 600
# 소비 지연 표본 간격(E13 시계열 10초 칸). 브로커에 직접 묻는다 — 앱의 records.lag 는 멈춘 동안 0을 보고한다(D-026).
LAG_INTERVAL_SECONDS = 10
# 예정 시각보다 이만큼 늦게 출발한 요청이 있으면 도착률을 지키지 못한 회차다.
LATE_START_MS = 100.0
# 결제 성공 주문 하나마다 한 번씩 처리해야 하는 소비 그룹(구매 여정, 2026-11-16 실측 기준).
PURCHASE_GROUPS = ["payment-order-request", "order-payment-result", "license-paid-order",
                   "order-license-grant", "settlement-paid-order"]


# 메시징을 쓰는 앱(Outbox 발행 또는 Inbox 소비). 재전달 차단 수를 이 앱들에서 읽는다. Store 는 DB(Inbox)가 없어 넣지 않는다.
MESSAGING_APPS = {"auth", "studio", "review", "catalog", "order", "payment", "license", "settlement"}
# 측정 회차의 Kafka 토픽 파티션 수(리스너 동시성 = 파티션 수, 결정 212). docker-compose.yml 의 KAFKA_NUM_PARTITIONS 와 같은 값이다.
PARALLELISM = 3


def extra_environment(assignments):
    """``--app-env NAME=VALUE`` 목록을 앱 환경으로. 형식이 틀리면 거절한다."""
    environment = {}
    for assignment in assignments:
        key, separator, value = assignment.partition("=")
        if not separator or not key:
            raise SystemExit("--app-env needs NAME=VALUE: " + assignment)
        environment[key] = value
    return environment


def load_command(offset, rate, seconds, buyers, evidence, warmup=10, refund_every=0):
    # 환불이 없으면 드라이버가 끝 상태를 HTTP로 묻지 않는다(0) — 부하 종료 뒤 해소 구간에 조회 부하를 더하지 않게.
    # 끝 상태는 DB 시각으로 따로 잰다(journey_timeseries).
    return ["./gradlew", ":e2e:messagingLoadTest", "--no-daemon", "--console=plain",
            "-Dstove.e2e.load-refund-every=" + str(refund_every),
            "-Dstove.e2e.load-settle-secs=" + ("180" if refund_every else "0"),
            "-Dstove.e2e.port-offset=" + str(offset),
            "-Dstove.e2e.load-rate=" + str(rate),
            "-Dstove.e2e.load-duration-secs=" + str(seconds),
            "-Dstove.e2e.load-warmup-secs=" + str(warmup),
            "-Dstove.e2e.load-buyers=" + str(buyers),
            "-Dstove.e2e.load-evidence=" + str(evidence)]


def journey_command(root, container, evidence, out):
    return [sys.executable, "-B", str(Path(root) / "tools/measurement/journey_latency.py"),
            "--container", container, "--evidence", str(evidence), "--wait-secs", str(OBSERVATION_SECONDS),
            "--out", str(out)]


def timeseries_command(root, container, evidence, out, load_ended_us):
    return [sys.executable, "-B", str(Path(root) / "tools/measurement/journey_timeseries.py"),
            "--container", container, "--evidence", str(evidence), "--wait-secs", str(OBSERVATION_SECONDS),
            "--load-ended-us", str(load_ended_us), "--out", str(out)]


def parse_group_lag(text):
    """``kafka-consumer-groups --describe --all-groups`` 출력 → {그룹: {"lag": 합, "unknown": 커밋 없는 파티션 수}}.

    커밋 오프셋이 없는 파티션(``-``)은 합에서 빼고 따로 센다 — 모르는 것을 0으로 적지 않는다(collect-lag.sh 와 같은 계산).
    """
    groups = {}
    header = None
    for line in text.splitlines():
        fields = line.split()
        if not fields:
            continue
        if fields[0] == "GROUP":
            header = fields
            continue
        if header is None or len(fields) < 6:
            continue
        row = dict(zip(header, fields))
        group = groups.setdefault(row["GROUP"], {"lag": 0, "unknown": 0})
        lag = row.get("LAG", "-")
        if lag.lstrip("-").isdigit() and row.get("CURRENT-OFFSET", "-") != "-":
            group["lag"] += int(lag)
        else:
            group["unknown"] += 1
    return groups


class LagSampler:
    """``interval``초마다 브로커의 소비 그룹 지연을 읽는다. 읽기 실패는 세기만 한다(측정을 멈추지 않는다)."""

    def __init__(self, capture, docker, interval=LAG_INTERVAL_SECONDS):
        self._command = docker + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh",
                                  "--bootstrap-server", "localhost:19092", "--describe", "--all-groups"]
        self._capture = capture
        self._interval = interval
        self._stopped = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self.samples = []
        self.failures = 0

    def start(self):
        self._thread.start()
        return self

    def stop(self):
        self._stopped.set()
        if self._thread.is_alive():
            self._thread.join()
        return self.samples

    def wait_for_zero_after(self, at_us, seconds):
        """``at_us`` 뒤에 지연 합 0 표본이 생길 때까지(최대 ``seconds``) 기다린다. ``at_us``가 없으면(미해소) 기다리지 않는다."""
        if at_us is None:
            return False
        deadline = time.monotonic() + max(0.0, seconds)
        while time.monotonic() < deadline:
            if any(sample["at_us"] >= at_us and sample.get("total") == 0 for sample in list(self.samples)):
                return True
            if self._stopped.wait(1.0):
                return False
        return False

    def _loop(self):
        while True:
            try:
                groups = parse_group_lag(self._capture(self._command, stderr=subprocess.DEVNULL))
                self.samples.append({"at_us": time.time_ns() // 1000, "groups": groups,
                                     "total": sum(group["lag"] for group in groups.values()),
                                     "unknown": sum(group["unknown"] for group in groups.values())})
            except Exception:  # noqa: BLE001 — 측정 보조값이라 회차를 멈추지 않고 실패만 센다
                self.failures += 1
            if self._stopped.wait(self._interval):
                return


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


def validity(measured, journey, broker, order=None, loaded=None, timeseries=None):
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
    inverted = ((timeseries or {}).get("clock_check") or {}).get("finish_before_start")
    if inverted:
        # 드라이버와 DB 시각을 한 시간축에 놓을 수 없다 — 시계열·해소 시간을 쓰지 않는다(PR #254 리뷰 R4).
        reasons.append("완료가 요청보다 앞선 주문 %d건(드라이버·DB 시각 불일치)" % inverted)
    window = (loaded or {}).get("measuredWindow") or {}
    target = (loaded or {}).get("rate")
    if window and target and window.get("achievedRate", 0) < target * 0.99:
        # 결정 231: 부하 발생기가 목표 요청률을 내지 못한 회차는 최대 성능 확정에 쓰지 않는다.
        reasons.append("부하 발생기 달성률 %.2f/s < 목표 %.2f/s의 99%%" % (window["achievedRate"], target))
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


def app_metrics_by_name(urls, apps):
    """``metrics_urls``와 같은 순서의 앱 이름 → {앱: URL}. 이름을 주지 않았으면 비운다."""
    return dict(zip(apps, urls)) if apps else {}


def load_end_time(loaded):
    """드라이버가 남긴 마지막 요청 시각(epoch µs). 없으면(옛 드라이버) 지금."""
    return loaded.get("lastRequestEpochUs") or time.time_ns() // 1000


def remaining_seconds(load_ended_us):
    """부하 종료 뒤 ``OBSERVATION_SECONDS`` 기한까지 남은 초."""
    return OBSERVATION_SECONDS - (time.time_ns() // 1000 - load_ended_us) / 1e6


def backlog_drain_seconds(load_ended_us, last_finish_us, unresolved, samples):
    """E13 Backlog 해소 시간: 부하 종료 → Outstanding 0(마지막 요청이 끝남)이고 소비 지연 0인 첫 표본. 관측 못 하면 None."""
    if unresolved or last_finish_us is None:
        return None
    for sample in samples:
        if sample["at_us"] >= last_finish_us and sample.get("total") == 0:
            return round((sample["at_us"] - load_ended_us) / 1e6, 3)
    return None


def with_faults(checked, schedule, restart=None):
    """장애 회차의 유효성은 "장애가 계획대로 일어나고 되돌려졌는가"다. 업무 결과(미완료·소비 누락 등)는 측정값이라
    ``outcome``으로 옮긴다 — 장애 회차에서 완료가 깨지는 것은 결과이지 무효 사유가 아니다(E13 시나리오 3~6).

    ``checked``는 업무 판정(``validity``)만 담은 것이어야 한다. 브로커 재시작(S7)의 주입 실패도 주입 실패라 무효 사유로 합친다
    (PR #254 리뷰 R1). 장애 계획이 없으면 기존처럼 S7 문제만 더한다."""
    if schedule is None or not schedule.faults:
        return with_restart(checked, restart)
    problems = schedule.problems() + (["S7 장애 주입: " + problem for problem in restart.problems()] if restart else [])
    return dict(checked, valid=not problems, reasons=problems, outcome=checked["reasons"])


def with_restart(checked, restart):
    """S7 회차면 장애가 계획대로 일어났는지도 유효 조건에 넣는다(PR #222 R1)."""
    if restart is None:
        return checked
    problems = restart.problems()
    if problems:
        checked = dict(checked, valid=False, reasons=checked["reasons"] + ["S7 장애 주입: " + problem for problem in problems])
    return checked


# 업무 DB(모든 모듈 스키마가 한 MySQL 서버)가 받은 조회 수.
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


# 실제 중단 시간이 계획과 이만큼 넘게 다르면 그 회차를 S7 결과로 쓰지 않는다(docker 명령 지연 포함).
DOWN_TOLERANCE_SECONDS = 2.0
# 재시작 뒤 진단을 이만큼만 더 기다린다. 넘으면 진단 실패로 남기고 회차는 계속한다.
DIAGNOSIS_JOIN_SECONDS = 30.0


class BrokerRestart:
    """S7(결정 210·204 D): 부하 중 ``at`` 초에 브로커 컨테이너를 강제 종료(``docker compose kill``)하고 ``down`` 초 뒤 다시 켠다.

    시각은 부하 드라이버 시작 기준이다. 명령 실패는 회차를 멈추지 않고 ``errors`` 에 남긴다(결과에서 장애가 실제로 일어났는지 본다).
    """

    def __init__(self, capture, docker, service, at, down, diagnose=None):
        self._capture = capture
        self._diagnose = diagnose
        self._docker = docker
        self.service = service
        self.at = at
        self.down = down
        self.killed_at = None
        self.started_at = None
        self.errors = []
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._origin = None

    def start(self):
        self._origin = time.monotonic()
        self._thread.start()
        return self

    def join(self):
        self._thread.join()

    def _step(self, command):
        try:
            self._capture(self._docker + command)
            return round(time.monotonic() - self._origin, 2)
        except Exception as failure:  # noqa: BLE001 — 측정을 멈추지 않고 남긴다
            self.errors.append("%s: %s" % (" ".join(command), str(failure)[-200:]))
            return None

    def _run(self):
        time.sleep(self.at)
        self.killed_at = self._step(["kill", self.service])
        # 재시작은 종료 시각 기준 절대 시각에 한다 — 진단이 오래 걸려도 중단 시간이 늘지 않게(PR #222 R3).
        restart_at = time.monotonic() + self.down
        diagnosis = None
        if self._diagnose is not None:
            # 브로커가 내려가 있는 한가운데에서 누가 DB 연결·잠금을 쥐고 있는지 남긴다(PR #222 S7 원인 조사).
            # 따로 돌려, 진단이 늦거나 멈춰도 재시작이 예정 시각을 지키게 한다(PR #222 R5).
            diagnosis = threading.Thread(target=self._run_diagnosis, daemon=True)
            diagnosis.start()
        time.sleep(max(0.0, restart_at - time.monotonic()))
        self.started_at = self._step(["start", self.service])
        if diagnosis is not None:
            diagnosis.join(DIAGNOSIS_JOIN_SECONDS)
            if diagnosis.is_alive():
                self.errors.append("diagnose: %.0f초 안에 끝나지 않았다" % DIAGNOSIS_JOIN_SECONDS)

    def _run_diagnosis(self):
        time.sleep(self.down / 2)
        try:
            self._diagnose()
        except Exception as failure:  # noqa: BLE001 — 진단 실패가 회차를 멈추지 않게
            self.errors.append("diagnose: " + str(failure)[-200:])

    def actual_down(self):
        if self.killed_at is None or self.started_at is None:
            return None
        return round(self.started_at - self.killed_at, 2)

    def problems(self):
        """장애가 계획대로 일어나지 않았으면 그 이유. 비어 있어야 S7 회차로 쓴다(PR #222 R1)."""
        reasons = []
        if self.killed_at is None:
            reasons.append("브로커 종료 실패")
        if self.started_at is None:
            reasons.append("브로커 재시작 실패")
        down = self.actual_down()
        if down is not None and abs(down - self.down) > DOWN_TOLERANCE_SECONDS:
            reasons.append("실제 중단 %.1f초(계획 %.1f초)" % (down, self.down))
        reasons.extend(error for error in self.errors if not error.startswith("diagnose: "))
        return reasons

    def summary(self):
        return {"service": self.service, "planned_at_s": self.at, "planned_down_s": self.down,
                "killed_at_s": self.killed_at, "started_at_s": self.started_at, "actual_down_s": self.actual_down(),
                "errors": self.errors}


# 장애 중 업무 DB 연결·트랜잭션(읽기 전용). 시스템 스레드는 뺀다.
RESTART_DB_SQL = ("SELECT id, user, db, command, time, state, LEFT(info, 200) FROM information_schema.processlist"
                  " WHERE user <> 'system user' AND user <> 'event_scheduler' ORDER BY db, time DESC;"
                  " SELECT trx_mysql_thread_id, trx_state, trx_started, trx_rows_locked, LEFT(trx_query, 200)"
                  " FROM information_schema.innodb_trx;")


def restart_diagnosis(capture, container, password, fetch, order_base_url, out):
    """S7 장애 중 진단을 ``out`` 파일에 남긴다: DB 세션·트랜잭션, order 의 Hikari 지표와 스레드 덤프(노출된 경우)."""
    parts = []
    try:
        parts.append("## processlist / innodb_trx\n" + capture(
            ["docker", "exec", "-i", container, "mysql", "-uroot", "-p" + password, "-B"],
            input=RESTART_DB_SQL, stderr=subprocess.STDOUT))
    except subprocess.CalledProcessError as failure:
        parts.append("## db 읽기 실패\n" + (failure.output or "")[-500:])
    if fetch and order_base_url:
        for name, path in (("hikari", "/actuator/prometheus"), ("threaddump", "/actuator/threaddump")):
            try:
                body = fetch(order_base_url + path)
                if name == "hikari":
                    body = "\n".join(line for line in body.splitlines() if line.startswith("hikaricp_"))
                parts.append("## order " + name + "\n" + body)
            except Exception as failure:  # noqa: BLE001 — 노출되지 않았으면 그렇다고 남긴다
                parts.append("## order " + name + " 읽기 실패: " + str(failure)[-200:])
    Path(out).write_text("\n\n".join(parts))


def measure(root, docker, run, capture, output, offset, rate, seconds, buyers, warmup=10, db_password="root1234",
            metrics_urls=(), fetch=None, refund_every=0,
            restart_at=None, restart_down=None, order_base_url=None, faults=(), store_metrics_url=None, metrics_apps=()):
    """한 회차를 돌고 요약을 돌려준다. ``run``·``capture``는 ``verify-jib.py``의 것을 받는다.

    부하 전 ``IDLE_SECONDS`` 동안의 업무 DB 초당 조회 수(유휴 비용)와 부하 드라이버 실행 구간(기동·예열 포함)의
    초당 조회 수를 같이 남긴다. 같은 두 구간의 브로커·업무 DB 컨테이너 CPU·메모리와 회차 전후 디스크도 남긴다(결정 210 S6).
    """
    load = Path(output) / "load.json"
    container = capture(docker + ["ps", "-q", "mysql"])
    containers = {service: capture(docker + ["ps", "-q", service])
                  for service in resource_sampling.SAMPLED_SERVICES}
    disk_before, disk_errors_before = resource_sampling.disk_kib(capture, containers)
    idle_sampler = resource_sampling.Sampler(capture, containers).start()
    try:
        idle_start = db_counters(capture, container, db_password)
        time.sleep(IDLE_SECONDS)
        idle_end = db_counters(capture, container, db_password)
    finally:
        # 유휴 읽기가 실패해도 표본 스레드가 계속 docker 를 부르지 않게 한다(PR #221 R3).
        idle_samples = idle_sampler.stop()
    schedule = None
    if faults:
        from support.e2e_faults import Context, FaultSchedule
        context = Context(capture, docker, db_password, fetch, store_metrics_url, run_tag=Path(output).name[-8:].lower(),
                          app_metrics=app_metrics_by_name(metrics_urls, metrics_apps))
        schedule = FaultSchedule(context, faults, str(load) + ".started").prepare()
    load_sampler = resource_sampling.Sampler(capture, containers).start()
    lag_sampler = LagSampler(capture, docker).start()
    try:
        if schedule is not None:
            schedule.start()
        restart = None
        if restart_at is not None:
            restart = BrokerRestart(capture, docker, resource_sampling.BROKER_SERVICE, restart_at, restart_down,
                                    lambda: restart_diagnosis(capture, container, db_password, fetch, order_base_url,
                                                              Path(output) / "restart-diagnosis.txt")).start()
        run(load_command(offset, rate, seconds, buyers, load, warmup, refund_every), "measure-load")
        # 부하 종료 = 드라이버의 마지막 요청 시각(결정 231 관찰 기한의 기준, PR #254 리뷰 R2). Gradle 종료·끝 상태 대기를 섞지 않는다.
        load_ended_us = load_end_time(json.loads(load.read_text()))
        if restart is not None:
            restart.join()
        # 부하가 끝난 바로 그때 읽는다. 표본 스레드 종료 대기(최대 표본 간격)가 부하 구간에 섞이지 않게(R4).
        load_end = db_counters(capture, container, db_password)
        if schedule is not None:
            # 부하보다 늦게 끝나는 장애(복구 확인 포함)를 관찰 기한 안에서 기다린다. 그동안에도 소비 지연 표본은 쌓인다.
            schedule.join(max(0.0, remaining_seconds(load_ended_us)))
    except BaseException:
        lag_sampler.stop()
        raise
    finally:
        load_samples = load_sampler.stop()
        if schedule is not None:
            schedule.cleanup()
    # 디스크도 부하 직후에 읽는다. 아래 수집 단계(읽기 전용 조회)가 증가량에 섞이지 않게(R6).
    disk_after, disk_errors_after = resource_sampling.disk_kib(capture, containers)
    diagnosis = refund_diagnosis(capture, container, db_password) if refund_every else None
    if diagnosis is not None:
        print("REFUND DIAGNOSIS " + json.dumps(diagnosis, ensure_ascii=False), flush=True)
    db_load = {"idle_per_second": db_rate(idle_start, idle_end), "load_run_per_second": db_rate(idle_end, load_end)}
    journey_file = Path(output) / "journey.json"
    timeseries_file = Path(output) / "timeseries.json"
    try:
        run(journey_command(root, container, load, journey_file), "measure-journey")
        run(timeseries_command(root, container, load, timeseries_file, load_ended_us), "measure-timeseries")
        last_finish_us = json.loads(timeseries_file.read_text()).get("last_finish_us")
        # E13 Backlog 해소 = 부하 종료 뒤 Outstanding 0 이고 소비 지연 0. 업무가 끝난 뒤 지연 0 표본이 올 때까지(기한 안) 더 잰다.
        lag_sampler.wait_for_zero_after(last_finish_us, remaining_seconds(load_ended_us))
    finally:
        # 수집이 실패해도 지연 표본이 docker 를 계속 부르지 않게 한다(PR #254 리뷰 R3).
        lag_samples = lag_sampler.stop()
    broker_file = Path(output) / "broker.json"
    run(broker_command(root, container, load, broker_file), "measure-broker")
    resources = {"interval_seconds": resource_sampling.INTERVAL_SECONDS,
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
    timeseries = json.loads(timeseries_file.read_text())
    timeseries["lag"] = {"interval_seconds": LAG_INTERVAL_SECONDS, "failures": lag_sampler.failures, "samples": lag_samples}
    timeseries["backlog_drain_seconds"] = backlog_drain_seconds(load_ended_us, timeseries.get("last_finish_us"),
                                                                timeseries.get("unresolved"), lag_samples)
    timeseries_file.write_text(json.dumps(timeseries, ensure_ascii=False, indent=2))
    http = [order["httpMs"] for order in measured if "httpMs" in order]
    late = [order["lateMs"] for order in measured if "lateMs" in order]
    return {"rate": rate, "duration_seconds": seconds, "warmup_seconds": warmup, "buyers": buyers,
            "planned": len(measured), "accepted": sum(1 for order in measured if order.get("status") == 200),
            "http_p95_ms": _nearest_rank(http, 95), "late_start_max_ms": max(late) if late else None,
            "journey": journey, "broker": broker, "order": order,
            "refunds": dict(zip(("requested", "rejected", "unfinished"), refund_problems(measured))),
            "refund_diagnosis": diagnosis,
            "redeliveries": redeliveries(fetch, metrics_urls) if fetch else None,
            "db_load": db_load, "resources": resources,
            "broker_restart": restart.summary() if restart is not None else None,
            "generator": loaded.get("measuredWindow"),
            "timeseries": {key: timeseries.get(key) for key in ("rates", "measured_orders", "totals", "unresolved",
                                                                 "outstanding_drain_seconds", "backlog_drain_seconds",
                                                                 "clock_check", "observation")},
            "load_ended_us": load_ended_us,
            "faults": schedule.summary() if schedule is not None else None,
            "validity": with_faults(validity(measured, journey, broker, order, loaded, timeseries), schedule, restart)}
