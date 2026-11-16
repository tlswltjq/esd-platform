#!/usr/bin/env python3
"""브로커 구간 지연(결정 204 B)을 소비 그룹별로 모은다. 업무 데이터는 읽기만 한다.

브로커 구간 = 소비 모듈 ``processed_event.processed_at`` − 발행 모듈 ``outbox_event.published_at``
(같은 ``event_id``). ``published_at``은 릴레이가 처음 보내기 직전 시각이다(PR #204). 격리 스택은
MySQL 하나에 모듈별 스키마 ``stove_<모듈>``을 두므로 스키마를 건너 한 번에 잇는다(``verify-jib.py``).

예:
    python3 -B tools/measurement/broker_latency.py --container <mysql 컨테이너> \\
        --keys-from runs/load.json --settle-secs 60 --out runs/broker.json

``--keys-from``이면 그 회차 주문번호(예열 제외)를 순서 키로 가진 이벤트만 센다. 구매 여정의 이벤트는 모두
주문번호를 키로 쓰므로 다른 회차·다른 업무(상품 출시 등)의 이벤트가 섞이지 않는다. 시각 창(``--since/--until``)은
수동 조사용으로 남긴다. ``--settle-secs``는 소비 그룹별 건수가 더 늘지 않을 때까지(5초 간격) 기다린다.

root 비밀번호는 ``MYSQL_ROOT_PASSWORD`` 환경 변수로 넘긴다(격리 스택 기본값 root1234).
결과의 음수 구간은 측정 결함이라 따로 센다(정상이면 0).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

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


_KEY = re.compile(r"^[A-Za-z0-9-]{1,64}$")


def _validate_schemas(publishers, consumers):
    for name in list(publishers) + list(consumers):
        if not _IDENTIFIER.match(name):
            raise ValueError("스키마 이름은 소문자만 허용한다: " + name)


def latency_sql_for_keys(publishers, consumers, keys):
    """이 순서 키들(주문번호)의 이벤트만 (발행, 소비) 스키마 쌍에서 event_id로 잇는다."""
    _validate_schemas(publishers, consumers)
    if not keys:
        raise ValueError("순서 키가 없다")
    for key in keys:
        if not _KEY.match(key):
            raise ValueError("순서 키 형식이 아니다: " + key)
    in_list = "(" + ",".join("'" + key + "'" for key in keys) + ")"
    parts = []
    for publisher in publishers:
        for consumer in consumers:
            parts.append(
                "SELECT p.consumer_group, o.event_type, TIMESTAMPDIFF(MICROSECOND, o.published_at, p.processed_at)"
                f" FROM stove_{publisher}.outbox_event o JOIN stove_{consumer}.processed_event p ON p.event_id = o.event_id"
                f" WHERE o.partition_key IN {in_list} AND o.published_at IS NOT NULL")
    return "\nUNION ALL\n".join(parts)


def latency_sql(publishers, consumers, since, until):
    """모든 (발행, 소비) 스키마 쌍을 event_id로 이어 (소비 그룹, 이벤트 종류, 구간 마이크로초)를 낸다."""
    _validate_schemas(publishers, consumers)
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


def mysql_command(container, sql):
    """비밀번호는 명령행에 두지 않는다. ``-e MYSQL_PWD``(이름만)로 자식 환경의 값을 컨테이너에 넘긴다."""
    return ["docker", "exec", "-i", "-e", "MYSQL_PWD", container, "mysql", "-uroot", "-N", "-B", "-e", sql]


def _mysql(container, password, sql):
    environment = dict(os.environ, MYSQL_PWD=password)
    completed = subprocess.run(mysql_command(container, sql), capture_output=True, text=True, env=environment)
    if completed.returncode != 0:
        # CalledProcessError 는 명령 전체(SQL)를 싣는다. 오류 문구만 남긴다.
        raise RuntimeError("mysql 실패(exit %d): %s" % (completed.returncode, completed.stderr.strip()[:500]))
    return completed.stdout


def keys_from(evidence):
    """부하 결과에서 측정 대상(접수됨·예열 아님) 주문번호."""
    return [order["orderNo"] for order in evidence.get("orders", [])
            if order.get("status") == 200 and order.get("orderNo") and not order.get("warmup")]


def counts(groups):
    return {group: values["count"] + values["negative"] for group, values in groups.items()}


def _collect(container, password, sql):
    output = _mysql(container, password, sql)
    return summarize([tuple(line.split("\t")) for line in output.splitlines() if line.strip()])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--container", required=True, help="격리 스택의 MySQL 컨테이너 이름")
    parser.add_argument("--keys-from", help="부하 드라이버 결과 JSON. 그 주문번호를 키로 가진 이벤트만 센다")
    parser.add_argument("--settle-secs", type=int, default=0, help="건수가 멈출 때까지 기다리는 최대 초")
    parser.add_argument("--since", help="측정 창 시작(DB 시각, published_at 기준). --keys-from이 없을 때")
    parser.add_argument("--until", help="측정 창 끝(포함하지 않음). --keys-from이 없을 때")
    parser.add_argument("--out", help="JSON 결과 파일. 없으면 표준 출력")
    args = parser.parse_args()
    password = os.environ.get("MYSQL_ROOT_PASSWORD", "root1234")

    schemas = _existing_schemas(args.container, password)
    publishers = [name for name in PUBLISHERS if "stove_" + name in schemas]
    consumers = [name for name in CONSUMERS if "stove_" + name in schemas]
    if args.keys_from:
        with open(args.keys_from, encoding="utf-8") as handle:
            keys = keys_from(json.load(handle))
        sql = latency_sql_for_keys(publishers, consumers, keys)
        scope = {"keys": len(keys)}
    elif args.since and args.until:
        sql = latency_sql(publishers, consumers, args.since, args.until)
        scope = {"since": args.since, "until": args.until}
    else:
        parser.error("--keys-from 또는 --since/--until이 필요하다")
    deadline = time.monotonic() + args.settle_secs
    groups = _collect(args.container, password, sql)
    while time.monotonic() < deadline:
        time.sleep(5)
        again = _collect(args.container, password, sql)
        settled = counts(again) == counts(groups)
        groups = again
        if settled:
            break
    result = {"scope": scope, "groups": groups}
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    else:
        sys.stdout.write(text + "\n")


if __name__ == "__main__":
    main()
