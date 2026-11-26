#!/usr/bin/env python3
"""Kafka 기준선 층 2 메시징 구간 회차를 한 번 돈다(E13·결정 230·233·234).

    python3 -B tools/measurement/bench_messaging.py run --name base-100-r1 --rate 100 --warmup 300 --seconds 900
    python3 -B tools/measurement/bench_messaging.py smoke

한 회차 = 격리된 MySQL·Kafka 컨테이너 새로 띄움 → 토픽 생성 → 시험 앱(별도 JVM) → 소비 그룹 배정 대기 → 부하 발생기(별도 JVM) →
최종 상태 대기(부하 종료 뒤 최대 10분, 결정 231) → DB·DLT 내보내기 → 시험 앱 정상 종료 → ``RunReport``(summary.json) →
그래프 → 컨테이너 정리. 회차마다 새 컨테이너라 앞 회차의 메시지·offset·Inbox가 섞이지 않는다(결정 230).

회차 폴더는 ``runs/E13/<이름>/``(``.gitignore``)다. 측정 PR이 ``summary.json``·그래프만 ``docs/experiments.d/E13/``로 옮기고
메시지별 원본은 실행 호스트 ``esd-run-archive/E13/``에 둔다(결정 233). 다른 컨테이너 대조는 이 스크립트 밖에서 실행 전후에
``tools/ci/measure_guard.py before/after --allow esd-bench-``로 한다(measurement-run skill).

``smoke``는 짧은 저부하 회차를 두 격리처 계열(격리 표·DLT)로 돌리고, 계측이 맞물리는지(유실 0·중복 반영 0·순서 위반 0, 주입한 영구
실패 = 최종 실패, 일시 실패는 재시도 뒤 완료, 수신 시도 기록 있음)를 판정한다. 성능 수치가 아니다(결정 230 Smoke 단계).
"""
import argparse
import datetime
import json
import os
import platform
import re
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "verification"))
from support import resource_sampling  # noqa: E402

KAFKA_IMAGE = "apache/kafka:3.9.0"
MYSQL_IMAGE = "mysql:8.0"
CONTAINER_PREFIX = "esd-bench-"
TOPIC = "bench.messages.v1"
CONSUMER_GROUP = "bench-messaging"  # RecordBenchMessage.CONSUMER_GROUP
DATABASE = "stove_bench"
ROOT_PASSWORD = "root1234"
APP_MAIN = "com.stove.bench.messaging.app.BenchApplication"
GENERATOR_MAIN = "com.stove.bench.messaging.load.LoadGenerator"
DUMP_MAIN = "com.stove.bench.messaging.load.TopicDump"
REPORT_MAIN = "com.stove.bench.messaging.report.RunReport"
# 결정 231: 복구 관찰 제한시간은 부하 종료 뒤 10분.
OBSERVATION_SECONDS = 600
LAG_INTERVAL_SECONDS = 10
_NAME = re.compile(r"^[a-z0-9][a-z0-9.-]{0,40}$")


def now_us():
    return time.time_ns() // 1_000


# ── 순수 함수(시험 대상) ──────────────────────────────────────────────

def kafka_run_args(name, port):
    """docker-compose.yml 의 kafka 와 같은 KRaft 단일 노드. 밖에서는 127.0.0.1:<port>, 안에서는 localhost:19092."""
    env = {
        "KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
        "KAFKA_LISTENERS": "PLAINTEXT://:19092,CONTROLLER://:9093,EXTERNAL://:9092",
        "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://localhost:19092,EXTERNAL://127.0.0.1:%d" % port,
        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT",
        "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@localhost:9093", "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
        "KAFKA_INTER_BROKER_LISTENER_NAME": "PLAINTEXT", "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
        "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1", "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
        "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "true", "KAFKA_NUM_PARTITIONS": "3",
    }
    args = ["docker", "run", "-d", "--rm", "--name", name, "-p", "127.0.0.1:%d:9092" % port]
    for key in sorted(env):
        args += ["-e", "%s=%s" % (key, env[key])]
    return args + [KAFKA_IMAGE]


def mysql_run_args(name, port):
    """docker-compose.yml 의 mysql 과 같은 이미지·문자셋. 시험 DB 하나와 앱 계정(stove)을 만든다."""
    return ["docker", "run", "-d", "--rm", "--name", name, "-p", "127.0.0.1:%d:3306" % port,
            "-e", "MYSQL_ROOT_PASSWORD=" + ROOT_PASSWORD, "-e", "MYSQL_DATABASE=" + DATABASE,
            "-e", "MYSQL_USER=stove", "-e", "MYSQL_PASSWORD=stove1234", "-e", "TZ=UTC",
            MYSQL_IMAGE, "--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci"]


def topic_create_args(container, topic, partitions, configs=None):
    args = ["docker", "exec", container, "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:19092",
            "--create", "--topic", topic, "--partitions", str(partitions), "--replication-factor", "1"]
    for key in sorted(configs or {}):
        args += ["--config", "%s=%s" % (key, configs[key])]
    return args


def parse_lag(text):
    """``kafka-consumer-groups.sh --describe`` 출력 → {"total", "partitions": {파티션: 지연}, "members"}. 지연이 '-'면 None."""
    partitions = {}
    members = 0
    header = None
    for line in text.splitlines():
        fields = line.split()
        if not fields:
            continue
        if fields[0] == "GROUP":
            header = fields
            continue
        if header is None or len(fields) < len(header):
            continue
        row = dict(zip(header, fields))
        if row.get("TOPIC") != TOPIC:
            continue
        lag = row.get("LAG")
        partitions[int(row["PARTITION"])] = int(lag) if lag and lag.lstrip("-").isdigit() else None
        if row.get("CONSUMER-ID", "-") != "-":
            members += 1
    known = [value for value in partitions.values() if value is not None]
    return {"total": sum(known) if known and len(known) == len(partitions) else None,
            "partitions": partitions, "members": members}


def prometheus_totals(text, names):
    """Prometheus 텍스트에서 이름별 값의 합(태그를 가리지 않음). 없으면 0이 아니라 None."""
    totals = {name: None for name in names}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        metric = line.split("{", 1)[0].split(" ", 1)[0]
        if metric in totals:
            value = float(line.rsplit(" ", 1)[1])
            totals[metric] = (totals[metric] or 0.0) + value
    return totals


METRICS = ("jvm_gc_pause_seconds_sum", "jvm_gc_pause_seconds_count", "stove_kafka_consumer_failed_deliveries_total",
           "stove_kafka_consumer_recovered_total", "stove_kafka_consumer_recovery_failures_total",
           "stove_kafka_dead_lettered_total", "stove_consumer_quarantine_records_total", "stove_inbox_duplicates_total", "hikaricp_connections_pending",
           "process_cpu_time_ns_total")


def metric_delta(before, after):
    delta = {}
    for name in METRICS:
        start, end = before.get(name), after.get(name)
        delta[name] = None if end is None else end - (start or 0.0)
    return delta


def proc_cpu_seconds(stat_text, ticks):
    """``/proc/<pid>/stat`` → 사용자+커널 CPU 초. 명령 이름에 공백·괄호가 있어도 마지막 ')' 뒤부터 센다."""
    fields = stat_text[stat_text.rindex(")") + 2:].split()
    return (int(fields[11]) + int(fields[12])) / ticks


def proc_rss_bytes(status_text):
    for line in status_text.splitlines():
        if line.startswith("VmRSS:"):
            return int(line.split()[1]) * 1024
    return None


def observation_deadline(monotonic_now, wall_now_us, load_ended_us):
    """부하 종료(벽시계 µs)에서 ``OBSERVATION_SECONDS`` 뒤를 지금의 단조 시계 값으로 옮긴다. 이미 지난 시간만큼 앞당긴다."""
    elapsed = max(0.0, (wall_now_us - load_ended_us) / 1e6)
    return monotonic_now + OBSERVATION_SECONDS - elapsed


def settled(counts, acked, lag_total):
    """최종 상태(반영·격리·DLT) 수가 확인된 발행 수에 닿고 소비 지연이 0이면 끝."""
    return lag_total == 0 and counts["final"] >= acked


def smoke_problems(summary, expect_dlt):
    """smoke 판정. 문제 목록(비면 통과)."""
    integrity, counts, retries = summary["integrity"], summary["counts"], summary["retries"]
    problems = []
    for key in ("lost", "duplicate_applied", "order_violations", "injected_but_completed", "unexpected_final_failures",
                "completed_and_failed", "unacked_but_delivered"):
        if integrity[key] != 0:
            problems.append("%s = %s (0이어야 한다)" % (key, integrity[key]))
    if counts["ack_failed"] != 0:
        problems.append("ack_failed = %s" % counts["ack_failed"])
    if integrity["expected_final_failures"] == 0:
        problems.append("영구 실패를 하나도 주입하지 않았다 — smoke 인자를 확인할 것")
    final = counts["dead_lettered"] if expect_dlt else counts["quarantined_failed"]
    if final != integrity["expected_final_failures"]:
        problems.append("최종 실패 %s건 ≠ 주입한 영구 실패 %s건" % (final, integrity["expected_final_failures"]))
    if expect_dlt and integrity["held_behind_stopped_key"] != 0:
        problems.append("DLT 계열인데 키 정지 보관이 있다")
    if retries["retry_succeeded"] == 0:
        problems.append("일시 실패 뒤 재시도로 완료된 메시지가 없다")
    if counts["attempts"] == 0:
        problems.append("수신 시도 기록이 없다")
    for key in ("completed_without_attempt_record", "attempt_log_malformed_lines"):
        if counts.get(key, 0) != 0:
            problems.append("%s = %s (수신 시도 기록이 빠졌거나 손상됐다)" % (key, counts[key]))
    return problems


# ── 실행 ─────────────────────────────────────────────────────────────

def capture(args, stderr=subprocess.PIPE, check=True, timeout=120):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=stderr, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError("실패(%d): %s\n%s" % (result.returncode, " ".join(args),
                                                 (result.stderr or b"").decode("utf-8", "replace")[-2000:]))
    return result.stdout.decode("utf-8", "replace")


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class Abort(RuntimeError):
    """기다려도 소용없는 실패(띄운 프로세스가 죽음). ``wait_until``이 삼키지 않는다."""


def wait_until(check, seconds, what, interval=1.0):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except Abort:
            raise
        except Exception as failure:  # noqa: BLE001 — 준비 중에는 실패가 정상이다
            last = failure
        time.sleep(interval)
    raise RuntimeError("%s 을(를) %d초 안에 확인하지 못했다: %s" % (what, seconds, last))


def alive(process, log):
    if process.poll() is not None:
        raise Abort("프로세스가 종료됐다(%d) — %s" % (process.returncode, log))
    return True


def mysql_query(container, sql):
    return capture(["docker", "exec", "-e", "MYSQL_PWD=" + ROOT_PASSWORD, container, "mysql", "-uroot", "-B", "-N",
                    "-D", DATABASE, "-e", "SET time_zone='+00:00'; " + sql])


def final_counts(container):
    text = mysql_query(container, "SELECT (SELECT COUNT(DISTINCT event_id) FROM bench_message), "
                                  "(SELECT COUNT(DISTINCT event_id) FROM consumer_quarantine)")
    completed, quarantined = (int(value) for value in text.split())
    return {"completed": completed, "quarantined": quarantined}


def scrape(port):
    with urllib.request.urlopen("http://127.0.0.1:%d/actuator/prometheus" % port, timeout=10) as response:
        return response.read().decode("utf-8")


def describe_lag(kafka):
    return parse_lag(capture(["docker", "exec", kafka, "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server",
                              "localhost:19092", "--describe", "--group", CONSUMER_GROUP], check=False))


def environment(java, run_dir):
    def read(path):
        try:
            return Path(path).read_text(encoding="utf-8")
        except OSError:
            return ""
    mem = re.search(r"MemTotal:\s+(\d+)", read("/proc/meminfo"))
    java_version = subprocess.run([java, "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.decode()
    commit = capture(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip()
    dirty = bool(capture(["git", "-C", str(ROOT), "status", "--porcelain"]).strip())
    images = {}
    for image in (KAFKA_IMAGE, MYSQL_IMAGE):
        images[image] = capture(["docker", "image", "inspect", "--format", "{{.Id}}", image], check=False).strip() or None
    return {"host": {"cpus": os.cpu_count(), "memory_kib": int(mem.group(1)) if mem else None,
                     "kernel": platform.release(), "machine": platform.machine()},
            "java": java_version.strip().splitlines(), "git_commit": commit, "git_dirty": dirty, "images": images,
            "disk": disk_environment(run_dir)}


def disk_environment(run_dir):
    """회차 폴더(시험 앱·발생기 기록)와 Docker 데이터 경로(MySQL·Kafka)가 놓인 파일 시스템(PR #253 리뷰 R3)."""
    docker_root = capture(["docker", "info", "--format", "{{.DockerRootDir}}\t{{.Driver}}"], check=False).strip()
    root, _, driver = docker_root.partition("\t")
    disks = {"docker_storage_driver": driver or None}
    for name, path in (("run_dir", str(run_dir)), ("docker_root", root)):
        lines = capture(["df", "-PT", path], check=False).strip().splitlines() if path else []
        disks[name] = df_line(lines[-1]) if len(lines) >= 2 else None
    return disks


def df_line(line):
    """``df -PT`` 한 줄 → 장치·파일 시스템 종류·크기(KiB)·마운트 위치."""
    fields = line.split()
    return {"device": fields[0], "type": fields[1], "size_kib": int(fields[2]), "mount": fields[6]}


class ProcessSampler:
    """호스트 JVM(시험 앱) 하나의 CPU·RSS를 ``interval``초마다 ``/proc``에서 읽는다."""

    def __init__(self, pid, interval=resource_sampling.INTERVAL_SECONDS):
        self.pid, self.interval = pid, interval
        self.ticks = os.sysconf("SC_CLK_TCK")
        self.samples = []

    def read(self):
        stat = Path("/proc/%d/stat" % self.pid).read_text()
        status = Path("/proc/%d/status" % self.pid).read_text()
        self.samples.append((time.monotonic(), proc_cpu_seconds(stat, self.ticks), proc_rss_bytes(status)))

    def summary(self):
        if len(self.samples) < 2:
            return {"samples": len(self.samples)}
        (t0, c0, _), (t1, c1, _) = self.samples[0], self.samples[-1]
        rss = [r for _, _, r in self.samples if r is not None]
        return {"samples": len(self.samples), "cpu_seconds": round(c1 - c0, 2),
                "cpu_percent_mean": round(100 * (c1 - c0) / (t1 - t0), 2),
                "rss_mib_max": round(max(rss) / 1024 ** 2, 1) if rss else None}


def run_once(options):
    if not _NAME.match(options.name):
        raise SystemExit("회차 이름은 소문자·숫자·점·하이픈 40자 이하다: " + options.name)
    run_dir = Path(options.run_root) / options.name
    if run_dir.exists():
        raise SystemExit("회차 폴더가 이미 있다(덮지 않는다): %s" % run_dir)
    run_dir.mkdir(parents=True)
    java = options.java
    capture([str(ROOT / "gradlew"), "-q", "-p", str(ROOT), ":benchmarks:messaging:writeBenchClasspath"], timeout=900)
    classpath = (ROOT / "benchmarks" / "messaging" / "build" / "bench-classpath.txt").read_text().strip()
    kafka, mysql = CONTAINER_PREFIX + options.name + "-kafka", CONTAINER_PREFIX + options.name + "-mysql"
    kafka_port, mysql_port, http_port = free_port(), free_port(), free_port()
    bootstrap = "127.0.0.1:%d" % kafka_port
    record = {"name": options.name, "recovery": options.recovery, "kills_us": [],
              "conditions": {"rate": options.rate, "warmup_seconds": options.warmup, "seconds": options.seconds,
                             "size": options.size, "keys": options.keys, "partitions": options.partitions,
                             "concurrency": options.concurrency or "기본값(결정 212: 3)",
                             "transient_ratio": options.transient_ratio, "transient_failures": options.transient_failures,
                             "permanent_ratio": options.permanent_ratio, "dlt_max_message_bytes": options.dlt_max_message_bytes,
                             "producer": "운영과 같음(acks=all·enable.idempotence=true·retries=3, linger.ms·batch.size·압축 지정 없음)",
                             "consumer": "운영과 같음(enable-auto-commit=false·ack-mode record·auto-offset-reset earliest, "
                                         "max.poll.records 지정 없음)",
                             "serialization": "키·값 StringSerializer/StringDeserializer, 값은 UTF-8 JSON 본문(운영과 같음)",
                             "retry": "1·2·4초 총 4회(ConsumerRetryPolicy)", "hikari_maximum_pool_size": 20,
                             "isolation": "READ COMMITTED(RecordBenchMessage)",
                             "app_jvm": options.app_jvm or "JVM 기본값", "generator_jvm": options.generator_jvm or "JVM 기본값"},
              "environment": environment(java, run_dir), "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    app = None
    generator = None
    sampler = None
    started = []
    try:
        capture(mysql_run_args(mysql, mysql_port))
        started.append(mysql)
        capture(kafka_run_args(kafka, kafka_port))
        started.append(kafka)
        wait_until(lambda: "mysqld is alive" in capture(["docker", "exec", "-e", "MYSQL_PWD=" + ROOT_PASSWORD, mysql,
                                                         "mysqladmin", "-uroot", "ping"], check=False), 120, "MySQL 기동")
        wait_until(lambda: mysql_query(mysql, "SELECT 1").strip() == "1", 120, "MySQL 시험 DB")
        wait_until(lambda: capture(["docker", "exec", kafka, "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server",
                                    "localhost:19092", "--list"]) is not None, 120, "Kafka 기동")
        capture(topic_create_args(kafka, TOPIC, options.partitions))
        dlt_configs = {"max.message.bytes": options.dlt_max_message_bytes} if options.dlt_max_message_bytes else None
        capture(topic_create_args(kafka, TOPIC + ".DLT", options.partitions, dlt_configs))

        env = dict(os.environ, DB_HOST="127.0.0.1", DB_PORT=str(mysql_port), DB_NAME=DATABASE, KAFKA_BROKERS=bootstrap,
                   BENCH_RUN_DIR=str(run_dir), BENCH_HTTP_PORT=str(http_port), BENCH_TOPIC=TOPIC)
        if options.recovery == "dlt":
            env["SPRING_PROFILES_ACTIVE"] = "dlt"
        if options.concurrency:
            env["SPRING_KAFKA_LISTENER_CONCURRENCY"] = str(options.concurrency)
        app_log = open(run_dir / "app.log", "wb")
        app = subprocess.Popen([java] + options.app_jvm.split() + ["-cp", classpath, APP_MAIN], env=env,
                               stdout=app_log, stderr=subprocess.STDOUT)
        wait_until(lambda: alive(app, run_dir / "app.log") and b'"UP"' in urllib.request.urlopen(
            "http://127.0.0.1:%d/actuator/health" % http_port, timeout=5).read(), 180, "시험 앱 health UP")
        wait_until(lambda: alive(app, run_dir / "app.log") and describe_lag(kafka)["members"] == options.partitions,
                   120, "소비 그룹 파티션 배정")

        containers = {"mysql": capture(["docker", "inspect", "--format", "{{.Id}}", mysql]).strip(),
                      "kafka": capture(["docker", "inspect", "--format", "{{.Id}}", kafka]).strip()}
        disk_before, _ = resource_sampling.disk_kib(capture, containers)
        sampler = resource_sampling.Sampler(capture, containers).start()
        app_sampler = ProcessSampler(app.pid)
        metrics_before = prometheus_totals(scrape(http_port), METRICS)
        lag = []
        record["load_started_us"] = now_us()
        generator_log = open(run_dir / "generator.log", "wb")
        generator = subprocess.Popen(
            [java] + options.generator_jvm.split() + ["-cp", classpath, GENERATOR_MAIN, "--bootstrap=" + bootstrap,
                                                     "--topic=" + TOPIC, "--run-dir=" + str(run_dir),
                                                     "--rate=%s" % options.rate, "--warmup=%d" % options.warmup,
                                                     "--seconds=%d" % options.seconds, "--size=%d" % options.size,
                                                     "--keys=%d" % options.keys,
                                                     "--transient-ratio=%s" % options.transient_ratio,
                                                     "--transient-failures=%d" % options.transient_failures,
                                                     "--permanent-ratio=%s" % options.permanent_ratio,
                                                     "--seed=%d" % options.seed],
            stdout=generator_log, stderr=subprocess.STDOUT)
        next_lag = time.monotonic()
        while generator.poll() is None:
            if time.monotonic() >= next_lag:
                lag.append(dict(describe_lag(kafka), at_us=now_us()))
                app_sampler.read()
                next_lag += LAG_INTERVAL_SECONDS
            time.sleep(0.5)
        if generator.returncode != 0:
            raise RuntimeError("부하 발생기가 실패했다(%d) — %s" % (generator.returncode, run_dir / "generator.log"))
        result = json.loads((run_dir / "generator.json").read_text())
        acked = result["acked"]
        # 결정 231의 10분은 부하 종료 순간부터다. 종료를 알아챈 때가 아니라 발생기가 남긴 종료 시각(마지막 확인까지 받은 순간)을
        # 기준으로 기한을 고정하고, 기한 안에 끝난 관측만 정착으로 본다(PR #253 리뷰 R2·재리뷰).
        record["load_ended_us"] = result["finished_us"]
        record["observation_deadline_us"] = record["load_ended_us"] + OBSERVATION_SECONDS * 1_000_000
        deadline = observation_deadline(time.monotonic(), now_us(), record["load_ended_us"])

        timed_out = True
        interval = LAG_INTERVAL_SECONDS if options.seconds > 60 else 2
        while time.monotonic() < deadline:
            reading = describe_lag(kafka)
            lag.append(dict(reading, at_us=now_us()))
            app_sampler.read()
            counts = final_counts(mysql)
            dead = prometheus_totals(scrape(http_port), ("stove_kafka_dead_lettered_total",))["stove_kafka_dead_lettered_total"]
            counts["final"] = counts["completed"] + counts["quarantined"] + int(dead or 0)
            if settled(counts, acked, reading["total"]) and time.monotonic() <= deadline:
                timed_out = False
                break
            time.sleep(max(0.0, min(interval, deadline - time.monotonic())))
        record["settled_us"] = None if timed_out else now_us()
        record["timed_out"] = timed_out
        record["metrics"] = metric_delta(metrics_before, prometheus_totals(scrape(http_port), METRICS))
        resources = resource_sampling.summarize(sampler.stop())
        sampler_failures, sampler_partial = sampler.failures, sampler.partial
        sampler = None
        disk_after, disk_errors = resource_sampling.disk_kib(capture, containers)
        record["resources"] = {"containers": resources, "sampler_failures": sampler_failures, "sampler_partial": sampler_partial,
                               "disk": resource_sampling.disk_growth(disk_before, disk_after), "disk_errors": disk_errors,
                               "app": app_sampler.summary()}
        record["lag"] = lag
        record["export_at_us"] = now_us()
        (run_dir / "bench_message.tsv").write_text(mysql_query(
            mysql, "SELECT id, event_id, message_key, attempt, completed_at_us FROM bench_message"), encoding="utf-8")
        (run_dir / "quarantine.tsv").write_text(mysql_query(
            mysql, "SELECT IFNULL(event_id, ''), status, failure = 'KEY_STOPPED', "
                   "CAST(UNIX_TIMESTAMP(quarantined_at) * 1000000 AS SIGNED) FROM consumer_quarantine "
                   "WHERE consumer_group = '%s'" % CONSUMER_GROUP), encoding="utf-8")
        capture([java, "-cp", classpath, DUMP_MAIN, "--bootstrap=" + bootstrap, "--topic=" + TOPIC + ".DLT",
                 "--out=" + str(run_dir / "dlt.csv")], timeout=300)
        app.terminate()
        app.wait(timeout=60)
        record["app_exit_code"] = app.returncode
        app = None
        (run_dir / "run.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        capture([java, "-cp", classpath, REPORT_MAIN, "--run-dir=" + str(run_dir)], timeout=600)
        capture([sys.executable, "-B", str(ROOT / "tools" / "measurement" / "bench_graphs.py"), str(run_dir / "summary.json")])
        return run_dir
    finally:
        # 예외로 빠져나와도 이 회차가 띄운 것은 모두 거둔다(PR #253 리뷰 R1): 발생기 → 샘플러 → 시험 앱 → 컨테이너.
        if generator is not None and generator.poll() is None:
            generator.terminate()
            try:
                generator.wait(timeout=30)
            except subprocess.TimeoutExpired:
                generator.kill()
                generator.wait(timeout=30)
        if sampler is not None:
            sampler.stop()
        if app is not None and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=60)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait(timeout=30)
        for container in reversed(started):
            # --rm 으로 띄웠으므로 멈추면 지워진다. 이 회차가 띄운 이름만 멈춘다.
            capture(["docker", "stop", container], check=False)


def smoke(options):
    failures = []
    for recovery in ("quarantine", "dlt"):
        options.name = "smoke-%s-%s" % (recovery, time.strftime("%Y%m%d%H%M%S"))
        options.recovery = recovery
        run_dir = run_once(options)
        summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
        problems = smoke_problems(summary, recovery == "dlt")
        if summary["run"].get("timed_out"):
            problems.append("최종 상태 대기가 제한시간을 넘었다")
        print("%s: %s" % (run_dir, "통과" if not problems else "실패"))
        for problem in problems:
            print("  - " + problem)
        failures += problems
    return 1 if failures else 0


def parser():
    root = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = root.add_subparsers(dest="command", required=True)
    for name in ("run", "smoke"):
        command = commands.add_parser(name)
        command.add_argument("--run-root", default=str(ROOT / "runs" / "E13"))
        command.add_argument("--java", default="java")
        command.add_argument("--app-jvm", default="", help="시험 앱 JVM 인자(예: -Xmx1g). 기본은 JVM 기본값")
        command.add_argument("--generator-jvm", default="")
        command.add_argument("--size", type=int, default=1024)
        command.add_argument("--keys", type=int, default=600)
        command.add_argument("--partitions", type=int, default=3)
        command.add_argument("--concurrency", type=int, default=None, help="리스너 동시성. 주지 않으면 운영 기본값(3)")
        command.add_argument("--seed", type=int, default=230)
        command.add_argument("--dlt-max-message-bytes", type=int, default=None, help="결정 235: DLT 토픽 max.message.bytes")
    run = commands.choices["run"]
    run.add_argument("--name", required=True)
    run.add_argument("--recovery", choices=("quarantine", "dlt"), default="quarantine")
    run.add_argument("--rate", type=float, required=True)
    run.add_argument("--warmup", type=int, required=True)
    run.add_argument("--seconds", type=int, required=True)
    run.add_argument("--transient-ratio", type=float, default=0.0)
    run.add_argument("--transient-failures", type=int, default=1)
    run.add_argument("--permanent-ratio", type=float, default=0.0)
    smoke_command = commands.choices["smoke"]
    smoke_command.set_defaults(rate=20.0, warmup=5, seconds=30, transient_ratio=0.05, transient_failures=1,
                               permanent_ratio=0.01)
    return root


def main(argv=None):
    options = parser().parse_args(argv)
    if options.command == "smoke":
        return smoke(options)
    print(run_once(options))
    return 0


if __name__ == "__main__":
    sys.exit(main())
