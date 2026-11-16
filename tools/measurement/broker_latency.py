#!/usr/bin/env python3
"""브로커 구간 지연(결정 204 B)을 소비 그룹별로 모은다. 업무 데이터는 읽기만 한다.

브로커 구간 = 소비 모듈 ``processed_event.processed_at`` − 발행 모듈 ``outbox_event.published_at``
(같은 ``event_id``). ``published_at``은 릴레이가 처음 보내기 직전 시각이다(PR #204). 격리 스택은
MySQL 하나에 모듈별 스키마 ``stove_<모듈>``을 두므로 스키마를 건너 한 번에 잇는다(``verify-jib.py``).

예:
    python3 -B tools/measurement/broker_latency.py --container <mysql 컨테이너> \\
        --since '2026-11-16 10:00:00' --until '2026-11-16 10:10:00' --out runs/broker.json

root 비밀번호는 ``MYSQL_ROOT_PASSWORD`` 환경 변수로 넘긴다(격리 스택 기본값 root1234).
결과의 음수 구간은 측정 결함이라 따로 센다(정상이면 0).
"""
import argparse
import json
import os
import re
import subprocess
import sys

# Outbox를 가진 발행 모듈과 processed_event를 가진 소비 모듈. Store는 Inbox가 없어 따로 계산한다(결정 204 B).
PUBLISHERS = ["auth", "catalog", "license", "order", "payment", "review", "settlement", "studio"]
CONSUMERS = ["catalog", "license", "order", "payment", "review", "settlement", "studio"]

_IDENTIFIER = re.compile(r"^[a-z]+$")
_DATETIME = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,6})?$")


def percentile(values, p):
    """가장 가까운 순위(nearest-rank) 백분위. 빈 목록이면 None."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, -(-p * len(ordered) // 100))
    return ordered[int(rank) - 1]


def latency_sql(publishers, consumers, since, until):
    """모든 (발행, 소비) 스키마 쌍을 event_id로 이어 (소비 그룹, 이벤트 종류, 구간 마이크로초)를 낸다."""
    for name in list(publishers) + list(consumers):
        if not _IDENTIFIER.match(name):
            raise ValueError("스키마 이름은 소문자만 허용한다: " + name)
    for value in (since, until):
        if not _DATETIME.match(value):
            raise ValueError("시각은 'YYYY-MM-DD HH:MM:SS[.ffffff]' 형식이어야 한다: " + value)
    parts = []
    for publisher in publishers:
        for consumer in consumers:
            parts.append(
                "SELECT p.consumer_group, o.event_type, TIMESTAMPDIFF(MICROSECOND, o.published_at, p.processed_at)"
                f" FROM stove_{publisher}.outbox_event o JOIN stove_{consumer}.processed_event p ON p.event_id = o.event_id"
                f" WHERE o.published_at >= '{since}' AND o.published_at < '{until}'")
    return "\nUNION ALL\n".join(parts)


def summarize(rows):
    """(소비 그룹, 이벤트 종류, 마이크로초) 행을 소비 그룹별 밀리초 요약으로 만든다."""
    spans = {}
    negative = {}
    for group, _event_type, micros in rows:
        value = int(micros)
        if value < 0:
            negative[group] = negative.get(group, 0) + 1
            continue
        spans.setdefault(group, []).append(value)
    summary = {}
    for group in sorted(set(spans) | set(negative)):
        values = spans.get(group, [])
        summary[group] = {
            "count": len(values),
            "negative": negative.get(group, 0),
            "p50_ms": _ms(percentile(values, 50)),
            "p95_ms": _ms(percentile(values, 95)),
            "p99_ms": _ms(percentile(values, 99)),
            "max_ms": _ms(max(values) if values else None),
        }
    return summary


def _ms(micros):
    return None if micros is None else micros / 1000


def _existing_schemas(container, password):
    output = _mysql(container, password, "SHOW DATABASES")
    return {line.strip() for line in output.splitlines()}


def _mysql(container, password, sql):
    command = ["docker", "exec", "-i", "-e", "MYSQL_PWD=" + password, container,
               "mysql", "-uroot", "-N", "-B", "-e", sql]
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--container", required=True, help="격리 스택의 MySQL 컨테이너 이름")
    parser.add_argument("--since", required=True, help="측정 창 시작(UTC, published_at 기준)")
    parser.add_argument("--until", required=True, help="측정 창 끝(UTC, 포함하지 않음)")
    parser.add_argument("--out", help="JSON 결과 파일. 없으면 표준 출력")
    args = parser.parse_args()
    password = os.environ.get("MYSQL_ROOT_PASSWORD", "root1234")

    schemas = _existing_schemas(args.container, password)
    publishers = [name for name in PUBLISHERS if "stove_" + name in schemas]
    consumers = [name for name in CONSUMERS if "stove_" + name in schemas]
    output = _mysql(args.container, password, latency_sql(publishers, consumers, args.since, args.until))
    rows = [tuple(line.split("\t")) for line in output.splitlines() if line.strip()]
    result = {"window": {"since": args.since, "until": args.until}, "groups": summarize(rows)}
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    else:
        sys.stdout.write(text + "\n")


if __name__ == "__main__":
    main()
