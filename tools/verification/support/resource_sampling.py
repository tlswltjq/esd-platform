"""측정 회차 동안 브로커·업무 DB 컨테이너의 CPU·메모리·디스크를 잰다(결정 210 S6, 결정 204 E).

- CPU·메모리: ``docker stats --no-stream`` 을 ``interval`` 초마다 읽는다. 메모리는 cgroup 사용량이라 페이지 캐시가
  섞인다 — Kafka 처럼 디스크 로그를 페이지 캐시로 읽는 브로커는 이 값이 커 보일 수 있다(결과에 조건으로 적는다).
- 디스크: 회차 전후 각 서비스 데이터 경로의 ``du -sk`` 합.

회차마다 업무 DB(``mysql``)와 브로커(``kafka``)를 잰다(결정 227: 메시징은 Kafka 하나).
"""
import re
import subprocess
import threading

# 메시지를 실어 나르는 Compose 서비스. S7 재시작(``BrokerRestart``)도 이 컨테이너를 내린다.
BROKER_SERVICE = "kafka"
# 회차마다 재는 Compose 서비스(업무 DB, 브로커).
SAMPLED_SERVICES = ("mysql", BROKER_SERVICE)
# 서비스별 데이터 경로. apache/kafka 이미지는 설정에 따라 두 경로 중 하나를 쓴다 — 있는 쪽만 합친다.
DATA_PATHS = {"mysql": ["/var/lib/mysql"], "kafka": ["/tmp/kraft-combined-logs", "/tmp/kafka-logs"]}
INTERVAL_SECONDS = 2.0
STATS_FORMAT = "{{.ID}}\t{{.CPUPerc}}\t{{.MemUsage}}"
_UNITS = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3, "kB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3}
_MEMORY = re.compile(r"^([0-9.]+)\s*([A-Za-z]+)$")


def memory_bytes(text):
    matched = _MEMORY.match(text.strip())
    if not matched or matched.group(2) not in _UNITS:
        raise ValueError("메모리 표기를 읽을 수 없다: " + text)
    return round(float(matched.group(1)) * _UNITS[matched.group(2)])


def parse_stats_line(line):
    """``ID\\tCPU%\\t사용 / 한도`` 한 줄 → (ID, CPU %, 사용 바이트)."""
    identifier, cpu, memory = line.split("\t")
    return identifier.strip(), float(cpu.strip().rstrip("%")), memory_bytes(memory.split("/")[0])


def parse_du(text):
    return sum(int(line.split("\t")[0]) for line in text.splitlines() if line.strip())


def summarize(samples):
    """(서비스, CPU %, 메모리 바이트) 표본 → 서비스별 평균·최댓값. 표본이 없는 서비스는 넣지 않는다(0 으로 적지 않는다)."""
    grouped = {}
    for service, cpu, memory in samples:
        grouped.setdefault(service, []).append((cpu, memory))
    summary = {}
    for service, values in sorted(grouped.items()):
        cpus = [cpu for cpu, _ in values]
        memories = [memory for _, memory in values]
        summary[service] = {"samples": len(values),
                            "cpu_percent_mean": round(sum(cpus) / len(cpus), 2), "cpu_percent_max": max(cpus),
                            "memory_mib_mean": round(sum(memories) / len(memories) / 1024 ** 2, 1),
                            "memory_mib_max": round(max(memories) / 1024 ** 2, 1)}
    return summary


class Sampler:
    """멈출 때까지 ``interval`` 초마다 ``docker stats`` 를 읽는다. 읽기 실패는 세기만 하고 회차를 멈추지 않는다."""

    def __init__(self, capture, containers, interval=INTERVAL_SECONDS):
        self._capture = capture
        self._services = {identifier: service for service, identifier in containers.items()}
        self._interval = interval
        self._samples = []
        self._stopped = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self.failures = 0
        self.partial = 0

    def start(self):
        self._thread.start()
        return self

    def stop(self):
        self._stopped.set()
        self._thread.join()
        return list(self._samples)

    def _loop(self):
        command = ["docker", "stats", "--no-stream", "--format", STATS_FORMAT] + list(self._services)
        # 구간이 짧아도 표본이 0개가 되지 않게 먼저 한 번 읽고 나서 멈춤을 본다.
        expected = sorted(self._services.values())
        while True:
            try:
                reading = []
                for line in self._capture(command).splitlines():
                    identifier, cpu, memory = parse_stats_line(line)
                    service = self._service_of(identifier)
                    if service:
                        reading.append((service, cpu, memory))
                self._samples.extend(reading)
                # 요청한 서비스가 한 번씩 다 왔는지 본다. 빠지거나 겹친 읽기는 불완전으로 센다(PR #221 R2).
                if sorted(service for service, _, _ in reading) != expected:
                    self.partial += 1
            except Exception:  # noqa: BLE001 — 측정 보조값이라 회차를 멈추지 않고 실패만 센다
                self.failures += 1
            if self._stopped.wait(self._interval):
                return

    def _service_of(self, identifier):
        # docker stats 는 짧은 ID(12자)를, docker compose ps -q 는 긴 ID를 준다.
        for full, service in self._services.items():
            if full.startswith(identifier) or identifier.startswith(full):
                return service
        return None


def du_script(paths):
    """있는 경로마다 ``du -sk``. 하나도 없거나 ``du`` 가 하나라도 실패하면 0이 아닌 종료 코드(PR #221 R1).

    apache/kafka 이미지처럼 설정에 따라 두 경로 중 하나만 쓰는 경우가 있어 "있는 것만"이되, 아무것도 없으면 실패다.
    """
    quoted = " ".join("'" + path + "'" for path in paths)
    return ("set -e; found=0; for p in " + quoted + "; do if [ -e \"$p\" ]; then du -sk \"$p\"; found=1; fi; done; "
            "[ \"$found\" = 1 ]")


def disk_kib(capture, containers):
    """서비스별 데이터 경로의 KiB 합과 읽지 못한 서비스의 이유. 읽지 못하면 값은 None(0 으로 적지 않는다)."""
    disk = {}
    errors = {}
    for service, identifier in containers.items():
        try:
            disk[service] = parse_du(capture(["docker", "exec", identifier, "sh", "-c", du_script(DATA_PATHS[service])],
                                             stderr=subprocess.STDOUT))
        except Exception as failure:  # noqa: BLE001 — 측정 보조값이라 회차를 멈추지 않고 이유를 남긴다
            disk[service] = None
            errors[service] = (getattr(failure, "output", None) or str(failure))[-300:]
    return disk, errors


def disk_growth(before, after):
    growth = {}
    for service in sorted(set(before) | set(after)):
        start, end = before.get(service), after.get(service)
        growth[service] = {"before_kib": start, "after_kib": end,
                           "growth_kib": end - start if start is not None and end is not None else None}
    return growth
