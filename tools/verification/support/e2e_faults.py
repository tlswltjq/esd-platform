"""층 1 E2E 회차의 외부 장애 주입(결정 229 층 1·결정 235, E13 시나리오 3~6). 앱 코드·이미지에 측정 코드를 넣지 않는다.

장애는 ``--fault <종류>:<키>=<값>,...``로 준다. 시각 ``at``은 부하 드라이버의 **첫 주문 예정 시각**(드라이버가 남기는
``<load.json>.started``)부터의 초다 — Gradle 기동·상품 출시가 섞이지 않는다.

- ``db-revoke:at=60,for=3,table=stove_license.product_license`` — 그 표의 ``INSERT`` 권한을 ``for``초 동안 회수한다.
  재시도 창(1·2·4초, 약 7초)보다 짧으면 일시 실패(시나리오 3), 길면 격리(시나리오 4). MySQL은 스키마 단위 권한 변경을 열린 연결에
  바로 적용하지 않으므로, 부하 전에 그 스키마의 권한을 표 단위로 바꾸고 그 스키마 연결을 한 번 끊어 둔다(``prepare``).
- ``kill:at=60,for=10,service=license`` — 컨테이너를 ``docker compose kill`` 하고 ``for``초 뒤 ``start``(시나리오 5·6).
- ``malformed:at=60,topic=stove.order.v1,count=1`` — 계약 헤더(``eventId``·``eventType``)가 없는 레코드를 직접 발행한다.
  키는 업무 주문과 겹치지 않는 ``e13-malformed-...``라 키 정지가 업무 주문을 막지 않는다. 그 토픽의 소비 그룹마다 격리 표에 남는다.
- ``dlt-limit:at=60,for=20,limit=512`` — 결정 235: Store DLT(``stove.catalog.v1.DLT``) 하나의 ``max.message.bytes``를 낮추고
  같은 키의 잘못된 봉투 2건을 ``stove.catalog.v1``에 넣는다. 거부(Store ``recovery-failures`` 증가)를 확인하고 ``for``초 뒤
  설정을 되돌린 뒤, 두 건이 순서대로 한 번씩 DLT에 저장되는지 본다. 설정 복원은 정상·오류 종료 모두에서 시도한다.

장애가 계획대로 일어나지 않았거나 되돌리지 못했으면 ``problems()``가 이유를 낸다. 그런 회차는 그 장애의 결과로 쓰지 않는다.
"""
import re
import threading
import time
from pathlib import Path

KAFKA_BIN = "/opt/kafka/bin/"
BOOTSTRAP = "localhost:19092"
STORE_TOPIC = "stove.catalog.v1"
STORE_DLT = STORE_TOPIC + ".DLT"
# apache/kafka 이미지는 설정 방식에 따라 둘 중 하나에 로그를 둔다(resource_sampling.DATA_PATHS 와 같은 후보).
KAFKA_DATA = ("/tmp/kraft-combined-logs", "/tmp/kafka-logs")
_TABLE = re.compile(r"^stove_[a-z]+\.[a-z_]+$")
_SERVICE = re.compile(r"^[a-z][a-z-]*$")
_TOPIC = re.compile(r"^[a-z0-9.]+$")
KINDS = ("db-revoke", "kill", "malformed", "dlt-limit")


def now_us():
    return time.time_ns() // 1_000


def parse(spec):
    """``종류:키=값,...`` → (종류, {키: 값}). 종류마다 필요한 키·형식을 검사한다."""
    kind, _, rest = spec.partition(":")
    if kind not in KINDS:
        raise ValueError("장애 종류는 %s 중 하나다: %s" % (", ".join(KINDS), spec))
    options = {}
    for part in filter(None, rest.split(",")):
        key, separator, value = part.partition("=")
        if not separator:
            raise ValueError("키=값 형식이 아니다: " + part)
        options[key] = value
    required = {"db-revoke": ("at", "for", "table"), "kill": ("at", "for", "service"),
                "malformed": ("at", "topic"), "dlt-limit": ("at", "for")}[kind]
    missing = [key for key in required if key not in options]
    if missing:
        raise ValueError("%s 에 %s 가 필요하다" % (kind, ", ".join(missing)))
    for key in ("at", "for"):
        if key in options and float(options[key]) < 0:
            raise ValueError("%s 는 0 이상이다" % key)
    if "table" in options and not _TABLE.match(options["table"]):
        raise ValueError("표는 stove_<모듈>.<표> 형식이다: " + options["table"])
    if "service" in options and not _SERVICE.match(options["service"]):
        raise ValueError("서비스 이름 형식이 아니다: " + options["service"])
    if "topic" in options and not _TOPIC.match(options["topic"]):
        raise ValueError("토픽 이름 형식이 아니다: " + options["topic"])
    return kind, options


def table_grant_sql(schema, tables, user="'stove'@'%'"):
    """스키마 단위 ALL 을 같은 표 단위 ALL 로 바꾼다. 표 단위 권한 변경은 열린 연결의 다음 요청부터 적용된다."""
    grants = ["GRANT ALL PRIVILEGES ON %s.%s TO %s;" % (schema, table, user) for table in tables]
    return " ".join(grants + ["REVOKE ALL PRIVILEGES ON %s.* FROM %s;" % (schema, user)])


def kill_schema_connections_sql(schema):
    """그 스키마에 붙은 앱 연결 id 를 고른다. 끊으면 풀이 새 연결(표 단위 권한)을 연다."""
    return ("SELECT id FROM information_schema.processlist WHERE user = 'stove' AND db = '%s';" % schema)


def log_dirs_script(topic):
    """두 로그 경로 후보에서 그 토픽의 파티션 폴더를 찾는다. 하나도 없으면 실패(빈 목록을 성공으로 넘기지 않는다)."""
    patterns = " ".join("%s/%s-*" % (base, topic) for base in KAFKA_DATA)
    return "found=0; for d in %s; do if [ -d \"$d\" ]; then echo \"$d\"; found=1; fi; done; [ \"$found\" = 1 ]" % patterns


def topic_config_value(describe_output, key):
    """``kafka-configs --describe`` 출력에서 토픽에 명시된 값. 명시되지 않았으면 None(기본값을 쓰는 중)."""
    for line in describe_output.splitlines():
        stripped = line.strip()
        if stripped.startswith(key + "="):
            return stripped.split(" ", 1)[0].split("=", 1)[1]
    return None


def partition_count(describe_output):
    matched = re.search(r"PartitionCount:\s*(\d+)", describe_output)
    return int(matched.group(1)) if matched else None


def batch_sizes(dump_output):
    """``kafka-dump-log --print-data-log`` 출력의 배치 크기들(``max.message.bytes``가 비교하는 값)."""
    return [int(value) for value in re.findall(r"\bsize: (\d+)", dump_output)]


def console_records(output, key):
    """``kafka-console-consumer`` (``print.timestamp``·``print.key``, 구분자 ``|``) 출력에서 그 키의 (타임스탬프 ms, 값)."""
    records = []
    for line in output.splitlines():
        parts = line.split("|", 2)
        if len(parts) == 3 and parts[1] == key and parts[0].startswith("CreateTime:"):
            records.append((int(parts[0].split(":", 1)[1]), parts[2]))
    return records


def prometheus_total(text, name):
    total = None
    for line in text.splitlines():
        if line.startswith(name + "{") or line.startswith(name + " "):
            total = (total or 0.0) + float(line.rsplit(" ", 1)[1])
    return total


class Context:
    """장애가 쓰는 실행 수단. ``capture(args, input=None)``은 명령 출력을 돌려주고 실패면 예외다."""

    def __init__(self, capture, docker, mysql_password, fetch=None, store_metrics_url=None, run_tag="e13", app_metrics=None):
        self.capture = capture
        self.docker = docker
        self.mysql_password = mysql_password
        self.fetch = fetch
        self.store_metrics_url = store_metrics_url
        self.run_tag = run_tag
        self.app_metrics = dict(app_metrics or {})

    def mysql(self, sql):
        return self.capture(self.docker + ["exec", "-T", "mysql", "mysql", "-uroot", "-p" + self.mysql_password,
                                           "-N", "-B", "-e", sql])

    def kafka(self, tool, *args, stdin=None):
        return self.capture(self.docker + ["exec", "-T", "kafka", KAFKA_BIN + tool, *args], input=stdin)

    def kafka_shell(self, command):
        return self.capture(self.docker + ["exec", "-T", "kafka", "sh", "-c", command])

    def store_metric(self, name):
        if self.fetch is None or self.store_metrics_url is None:
            return None
        return prometheus_total(self.fetch(self.store_metrics_url), name)

    def app_metric(self, app, name):
        """앱 지표 합. 읽을 수단이 없으면 None, 지표가 아직 한 번도 생기지 않았으면 0(카운터는 처음 셀 때 생긴다)."""
        if self.fetch is None or app not in self.app_metrics:
            return None
        return prometheus_total(self.fetch(self.app_metrics[app]), name) or 0.0


class Fault:
    """장애 하나. ``prepare``는 부하 전에, ``run``은 시각이 되면 별도 스레드에서 돈다. 사건은 epoch µs로 남긴다."""

    kind = ""

    def __init__(self, context, options):
        self.context = context
        self.options = options
        self.at = float(options["at"])
        self.duration = float(options.get("for", 0))
        self.events = []
        self.errors = []
        self.facts = {}

    def mark(self, name):
        self.events.append({"event": name, "at_us": now_us()})

    def step(self, name, action):
        try:
            result = action()
            self.mark(name)
            return result
        except Exception as failure:  # noqa: BLE001 — 회차를 멈추지 않고 남긴다(결과에서 장애가 실제로 일어났는지 본다)
            self.errors.append("%s: %s" % (name, str(failure)[-300:]))
            return None

    def prepare(self):
        pass

    def run(self):
        raise NotImplementedError

    def cleanup(self):
        """정상·오류 종료 모두에서 부른다. 되돌릴 것이 남아 있으면 되돌린다."""

    def problems(self):
        return list(self.errors)

    def summary(self):
        return {"kind": self.kind, "options": self.options, "events": self.events, "facts": self.facts,
                "problems": self.problems()}


class DbRevoke(Fault):
    kind = "db-revoke"

    def __init__(self, context, options):
        super().__init__(context, options)
        self.schema, self.table = options["table"].split(".")
        self.revoked = False

    def prepare(self):
        tables = [line.strip() for line in self.context.mysql(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = '%s';" % self.schema).splitlines()
            if line.strip()]
        if self.table not in tables:
            self.errors.append("표가 없다: %s.%s" % (self.schema, self.table))
            return
        self.step("table-grants", lambda: self.context.mysql(table_grant_sql(self.schema, tables)))
        ids = [line.strip() for line in self.context.mysql(kill_schema_connections_sql(self.schema)).splitlines()
               if line.strip()]
        for identifier in ids:
            # 이미 끊긴 연결이면 실패해도 된다.
            try:
                self.context.mysql("KILL %d;" % int(identifier))
            except Exception:  # noqa: BLE001
                pass
        self.facts["prepared_tables"] = len(tables)
        self.facts["reconnected"] = len(ids)

    FAILED_DELIVERIES = "stove_kafka_consumer_failed_deliveries_total"

    def run(self):
        app = self.schema[len("stove_"):]
        before = self.context.app_metric(app, self.FAILED_DELIVERIES)
        sql = "REVOKE INSERT ON %s.%s FROM 'stove'@'%%';" % (self.schema, self.table)
        if self.step("revoke", lambda: self.context.mysql(sql)) is not None:
            self.revoked = True
        time.sleep(self.duration)
        self.restore()
        # 재시도가 끝날 시간(1·2·4초)을 두고 읽는다. 회수 동안 소비 실패가 있었는지가 "장애가 효과를 냈는가"다.
        time.sleep(8)
        after = self.context.app_metric(app, self.FAILED_DELIVERIES)
        self.facts["failed_deliveries_during"] = None if before is None or after is None else after - before

    def restore(self):
        if not self.revoked:
            return
        sql = "GRANT INSERT ON %s.%s TO 'stove'@'%%';" % (self.schema, self.table)
        if self.step("grant", lambda: self.context.mysql(sql)) is not None:
            self.revoked = False

    def cleanup(self):
        self.restore()

    def problems(self):
        reasons = list(self.errors)
        names = [event["event"] for event in self.events]
        if "revoke" not in names:
            reasons.append("권한 회수 실패")
        if self.revoked or "grant" not in names:
            reasons.append("권한 복원 실패")
        if self.facts.get("failed_deliveries_during") == 0:
            reasons.append("권한 회수 동안 소비 실패가 없었다(장애가 효과를 내지 못했다)")
        return reasons


class ContainerKill(Fault):
    kind = "kill"

    def run(self):
        service = self.options["service"]
        self.step("kill", lambda: self.context.capture(self.context.docker + ["kill", service]))
        time.sleep(self.duration)
        self.step("start", lambda: self.context.capture(self.context.docker + ["start", service]))

    def problems(self):
        reasons = list(self.errors)
        names = [event["event"] for event in self.events]
        for name in ("kill", "start"):
            if name not in names:
                reasons.append("%s 실패" % name)
        return reasons


def malformed_lines(key_prefix, count):
    """``kafka-console-producer`` (``parse.key``, 구분자 ``|``) 입력. 헤더를 붙이지 않으므로 계약 위반이다."""
    return "".join('%s-%d|{"e13":"malformed","seq":%d}\n' % (key_prefix, index, index) for index in range(count))


class MalformedEnvelopes(Fault):
    """계약 헤더 없는 레코드는 소비 그룹마다 봉투 검증에서 거절돼 재시도(1·2·4초) 뒤 격리된다. 구독 그룹마다 격리 표 행을 확인한다."""

    kind = "malformed"
    QUARANTINE_WAIT_SECONDS = 60

    def run(self):
        count = int(self.options.get("count", 1))
        prefix = "e13-malformed-%s" % self.context.run_tag
        self.facts["key_prefix"] = prefix
        self.step("publish", lambda: self.context.kafka(
            "kafka-console-producer.sh", "--bootstrap-server", BOOTSTRAP, "--topic", self.options["topic"],
            "--property", "parse.key=true", "--property", "key.separator=|",
            stdin=malformed_lines(prefix, count)))
        self.facts["published"] = count
        expected = sorted(self.subscribed_groups())
        self.facts["subscribed_groups"] = expected
        observed = []
        started = time.monotonic()
        while time.monotonic() - started < self.QUARANTINE_WAIT_SECONDS:
            observed = self.quarantined_groups(prefix)
            if observed == expected:
                break
            time.sleep(5)
        self.facts["quarantined_groups"] = observed

    def subscribed_groups(self):
        try:
            output = self.context.kafka("kafka-consumer-groups.sh", "--bootstrap-server", BOOTSTRAP, "--describe", "--all-groups")
        except Exception as failure:  # noqa: BLE001
            self.errors.append("소비 그룹을 읽지 못했다: " + str(failure)[-200:])
            return []
        return sorted({line.split()[0] for line in output.splitlines()
                       if len(line.split()) > 2 and line.split()[1] == self.options["topic"]})

    def quarantined_groups(self, prefix):
        schemas = [line.strip() for line in self.context.mysql(
            "SELECT table_schema FROM information_schema.tables WHERE table_name = 'consumer_quarantine';").splitlines()
            if line.strip()]
        if not schemas:
            return []
        union = " UNION ".join("SELECT DISTINCT consumer_group FROM %s.consumer_quarantine WHERE partition_key LIKE '%s-%%'"
                               % (schema, prefix) for schema in schemas)
        return sorted(line.strip() for line in self.context.mysql(union + ";").splitlines() if line.strip())

    def problems(self):
        reasons = list(self.errors)
        if "publish" not in [event["event"] for event in self.events]:
            reasons.append("발행 실패")
        expected, observed = self.facts.get("subscribed_groups"), self.facts.get("quarantined_groups")
        if not expected:
            reasons.append("그 토픽을 구독하는 소비 그룹을 찾지 못했다")
        elif observed != expected:
            reasons.append("격리된 그룹 %s ≠ 구독 그룹 %s" % (observed, expected))
        return reasons


class DltLimit(Fault):
    """결정 235. 원본 토픽과 브로커는 그대로 두고 Store DLT 토픽 하나의 쓰기만 크기 제한으로 거부시킨다."""

    kind = "dlt-limit"
    RESTORE_WAIT_SECONDS = 120

    def __init__(self, context, options):
        super().__init__(context, options)
        self.limit = int(options.get("limit", 512))
        self.original = None
        self.limited = False
        self.restored_wall = None

    def configs(self, *args):
        return self.context.kafka("kafka-configs.sh", "--bootstrap-server", BOOTSTRAP, "--entity-type", "topics",
                                  "--entity-name", STORE_DLT, *args)

    def inspect(self):
        """원본 토픽은 드라이버가 상품을 출시해야 생긴다 — 부하 전이 아니라 장애 시각에 살핀다."""
        partitions = partition_count(self.context.kafka("kafka-topics.sh", "--bootstrap-server", BOOTSTRAP,
                                                        "--describe", "--topic", STORE_TOPIC))
        self.facts["partitions"] = partitions
        if partitions is None:
            self.errors.append("원본 토픽 %s 의 파티션 수를 읽지 못했다" % STORE_TOPIC)
            return
        # DeadLetterPublisher 는 원본과 같은 파티션으로 보낸다 — DLT 를 같은 파티션 수로 미리 만든다.
        self.step("dlt-topic", lambda: self.context.kafka(
            "kafka-topics.sh", "--bootstrap-server", BOOTSTRAP, "--create", "--if-not-exists", "--topic", STORE_DLT,
            "--partitions", str(partitions), "--replication-factor", "1"))
        self.original = topic_config_value(self.configs("--describe"), "max.message.bytes")
        self.facts["original_max_message_bytes"] = self.original or "기본값(명시 설정 없음)"

    def run(self):
        self.inspect()
        if self.facts.get("partitions") is None:
            return
        key = "e13-dlt-%s" % self.context.run_tag
        self.facts["key"] = key
        # 카운터는 처음 셀 때 생긴다. 아직 없으면 0으로 읽는다(읽을 수단이 없을 때만 None).
        failures_before = self.store_count("stove_kafka_consumer_recovery_failures_total")
        dead_before = self.store_count("stove_kafka_dead_lettered_total")
        if self.step("limit", lambda: self.configs("--alter", "--add-config", "max.message.bytes=%d" % self.limit)) is not None:
            self.limited = True
        self.step("publish", lambda: self.context.kafka(
            "kafka-console-producer.sh", "--bootstrap-server", BOOTSTRAP, "--topic", STORE_TOPIC,
            "--property", "parse.key=true", "--property", "key.separator=|",
            stdin='%s|{"e13":"dlt","seq":0}\n%s|{"e13":"dlt","seq":1}\n' % (key, key)))
        time.sleep(self.duration)
        failures_during = self.store_count("stove_kafka_consumer_recovery_failures_total")
        dead_during = self.store_count("stove_kafka_dead_lettered_total")
        self.facts["recovery_failures_during"] = (None if failures_during is None or failures_before is None
                                                  else failures_during - failures_before)
        self.facts["dead_lettered_during"] = (None if dead_during is None or dead_before is None
                                              else dead_during - dead_before)
        self.facts["dlt_records_during"] = len(self.dlt_records(key))
        self.restore()
        restored_at = time.monotonic()
        records = []
        while time.monotonic() - restored_at < self.RESTORE_WAIT_SECONDS:
            records = self.dlt_records(key)
            if len(records) >= 2:
                break
            time.sleep(2)
        self.facts["dlt_records_after"] = len(records)
        self.facts["dlt_order"] = [value for _, value in records]
        if records and self.restored_wall is not None:
            self.facts["resume_seconds_after_restore"] = round(records[0][0] / 1000 - self.restored_wall, 3)
        sizes = self.batch_sizes()
        self.facts["dlt_batch_bytes"] = sizes
        self.facts["limit_below_record"] = bool(sizes) and self.limit < min(sizes)

    def store_count(self, name):
        if self.context.fetch is None or self.context.store_metrics_url is None:
            return None
        return self.context.store_metric(name) or 0.0

    def dlt_records(self, key):
        try:
            output = self.context.kafka("kafka-console-consumer.sh", "--bootstrap-server", BOOTSTRAP, "--topic", STORE_DLT,
                                        "--from-beginning", "--timeout-ms", "6000", "--property", "print.timestamp=true",
                                        "--property", "print.key=true", "--property", "key.separator=|")
        except Exception as failure:  # noqa: BLE001 — 시간 초과로 끝나도 읽은 것은 출력에 있다
            output = getattr(failure, "output", "") or ""
        return console_records(output, key)

    def batch_sizes(self):
        try:
            listing = self.context.kafka_shell(log_dirs_script(STORE_DLT))
        except Exception as failure:  # noqa: BLE001
            self.errors.append("DLT 로그 위치를 읽지 못했다: " + str(failure)[-200:])
            return []
        sizes = []
        for directory in filter(None, listing.split()):
            try:
                sizes += batch_sizes(self.context.kafka("kafka-dump-log.sh", "--print-data-log", "--files",
                                                        directory + "/00000000000000000000.log"))
            except Exception:  # noqa: BLE001 — 비어 있는 파티션은 로그 파일이 없을 수 있다
                continue
        return sizes

    def restore(self):
        if not self.limited:
            return
        args = (["--alter", "--delete-config", "max.message.bytes"] if self.original is None
                else ["--alter", "--add-config", "max.message.bytes=" + self.original])
        if self.step("restore", lambda: self.configs(*args)) is not None:
            self.limited = False
            self.restored_wall = time.time()
            current = topic_config_value(self.configs("--describe"), "max.message.bytes")
            self.facts["restored_value"] = current or "기본값(명시 설정 없음)"
            if current != self.original:
                self.errors.append("복원 뒤 설정이 원래와 다르다: %s → %s" % (self.original, current))

    def cleanup(self):
        self.restore()

    def problems(self):
        reasons = list(self.errors)
        names = [event["event"] for event in self.events]
        for name in ("limit", "publish", "restore"):
            if name not in names:
                reasons.append("%s 실패" % name)
        if self.limited:
            reasons.append("DLT 설정 복원 실패")
        if not self.facts.get("recovery_failures_during"):
            reasons.append("DLT 쓰기 실패를 일으키지 못했다(Store recovery-failures 증가 없음)")
        if self.facts.get("dead_lettered_during") is None:
            reasons.append("장애 중 DLT 지표를 읽지 못했다")
        elif self.facts["dead_lettered_during"]:
            reasons.append("장애 중 DLT 지표가 성공을 셌다 %s" % self.facts["dead_lettered_during"])
        if self.facts.get("dlt_records_during"):
            reasons.append("장애 중 DLT 에 저장됐다 %s건" % self.facts["dlt_records_during"])
        if self.facts.get("dlt_records_after") != 2:
            reasons.append("복구 뒤 DLT 저장 %s건(기대 2, 중복·유실 0)" % self.facts.get("dlt_records_after"))
        elif self.facts.get("dlt_order") != ['{"e13":"dlt","seq":0}', '{"e13":"dlt","seq":1}']:
            reasons.append("DLT 순서가 발행 순서와 다르다")
        if self.facts.get("limit_below_record") is False:
            reasons.append("제한(%d)이 실제 DLT 배치 크기 %s 보다 작지 않다" % (self.limit, self.facts.get("dlt_batch_bytes")))
        return reasons


FAULTS = {"db-revoke": DbRevoke, "kill": ContainerKill, "malformed": MalformedEnvelopes, "dlt-limit": DltLimit}


class FaultSchedule:
    """장애들을 첫 주문 시각 기준으로 돌린다. ``marker``가 생길 때까지(최대 ``wait``초) 기다린다."""

    def __init__(self, context, specs, marker, wait=600):
        self.context = context
        self.faults = [FAULTS[kind](context, options) for kind, options in (parse(spec) for spec in specs)]
        self.marker = Path(marker)
        self.wait = wait
        self.load_started_us = None
        self.errors = []
        self._threads = []
        self._driver = threading.Thread(target=self._run, daemon=True)

    def prepare(self):
        for fault in self.faults:
            fault.prepare()
        return self

    def start(self):
        if self.faults:
            self._driver.start()
        return self

    def _run(self):
        deadline = time.monotonic() + self.wait
        while not self.marker.exists():
            if time.monotonic() > deadline:
                self.errors.append("부하 시작 표시(%s)가 생기지 않았다" % self.marker.name)
                return
            time.sleep(0.2)
        self.load_started_us = int(self.marker.read_text().strip())
        origin = time.monotonic() - max(0.0, (now_us() - self.load_started_us) / 1e6)
        for fault in sorted(self.faults, key=lambda f: f.at):
            time.sleep(max(0.0, origin + fault.at - time.monotonic()))
            thread = threading.Thread(target=self._guarded, args=(fault,), daemon=True)
            thread.start()
            self._threads.append(thread)

    @staticmethod
    def _guarded(fault):
        try:
            fault.run()
        except Exception as failure:  # noqa: BLE001
            fault.errors.append("run: " + str(failure)[-300:])

    def join(self, timeout=None):
        if self._driver.is_alive():
            self._driver.join(timeout)
        for thread in self._threads:
            thread.join(timeout)

    def cleanup(self):
        for fault in self.faults:
            try:
                fault.cleanup()
            except Exception as failure:  # noqa: BLE001
                fault.errors.append("cleanup: " + str(failure)[-300:])

    def problems(self):
        reasons = list(self.errors)
        for fault in self.faults:
            reasons += ["%s: %s" % (fault.kind, problem) for problem in fault.problems()]
        return reasons

    def summary(self):
        return {"load_started_us": self.load_started_us, "faults": [fault.summary() for fault in self.faults],
                "problems": self.problems()}
