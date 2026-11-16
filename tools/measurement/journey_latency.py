#!/usr/bin/env python3
"""구매 업무 완료 시간(결정 204 A)을 DB 시각으로 잰다. 업무 데이터는 읽기만 한다.

- 주문 생성 → 구매 근거: ``stove_order.orders.created_at`` → ``stove_license.license_purchase.received_at``
- 결제 확정 → 구매 근거: ``orders.paid_at`` → ``license_purchase.received_at``

끝점을 보유권 ``ACTIVE``가 아니라 보관함의 구매 근거로 둔다. 결정 93(결제 뒤 보유권은 보관함에 생기고
구매자가 등록해야 라이브러리에 나타난다) 때문에 등록 시각은 사람의 행동이라 브로커 비교에 맞지 않는다.
구매 근거가 아직 없는 주문(완료 전)은 따로 센다.

예:
    python3 -B tools/measurement/journey_latency.py --container <mysql 컨테이너> \\
        --since '2026-11-16 10:00:00' --until '2026-11-16 10:10:00' --out runs/journey.json
"""
import argparse
import json
import os
import sys

import broker_latency


def purchase_sql(since, until):
    for value in (since, until):
        if not broker_latency._DATETIME.match(value):
            raise ValueError("시각은 'YYYY-MM-DD HH:MM:SS[.ffffff]' 형식이어야 한다: " + value)
    return ("SELECT o.order_no,"
            " TIMESTAMPDIFF(MICROSECOND, o.created_at, lp.received_at),"
            " TIMESTAMPDIFF(MICROSECOND, o.paid_at, lp.received_at)"
            " FROM stove_order.orders o JOIN stove_license.license_purchase lp"
            " ON lp.order_no = CONVERT(o.order_no USING ascii) COLLATE ascii_bin"
            f" WHERE o.created_at >= '{since}' AND o.created_at < '{until}'"
            "\nUNION ALL\n"
            "SELECT o.order_no, NULL, NULL FROM stove_order.orders o"
            f" WHERE o.created_at >= '{since}' AND o.created_at < '{until}'"
            " AND NOT EXISTS (SELECT 1 FROM stove_license.license_purchase lp"
            " WHERE lp.order_no = CONVERT(o.order_no USING ascii) COLLATE ascii_bin)")


def summarize(rows):
    created, paid = [], []
    incomplete = 0
    for _order_no, created_micros, paid_micros in rows:
        if created_micros in (None, "NULL"):
            incomplete += 1
            continue
        created.append(int(created_micros))
        if paid_micros not in (None, "NULL"):
            paid.append(int(paid_micros))
    return {"completed": len(created), "incomplete": incomplete,
            "created_to_basis": _span(created), "paid_to_basis": _span(paid)}


def _span(values):
    return {"p50_ms": broker_latency._ms(broker_latency.percentile(values, 50)),
            "p95_ms": broker_latency._ms(broker_latency.percentile(values, 95)),
            "p99_ms": broker_latency._ms(broker_latency.percentile(values, 99)),
            "max_ms": broker_latency._ms(max(values) if values else None)}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--container", required=True)
    parser.add_argument("--since", required=True, help="측정 창 시작(UTC, 주문 생성 기준)")
    parser.add_argument("--until", required=True, help="측정 창 끝(UTC, 포함하지 않음)")
    parser.add_argument("--out")
    args = parser.parse_args()
    password = os.environ.get("MYSQL_ROOT_PASSWORD", "root1234")
    output = broker_latency._mysql(args.container, password, purchase_sql(args.since, args.until))
    rows = [tuple(line.split("\t")) for line in output.splitlines() if line.strip()]
    text = json.dumps({"window": {"since": args.since, "until": args.until}, "purchase": summarize(rows)},
                      ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    else:
        sys.stdout.write(text + "\n")


if __name__ == "__main__":
    main()
