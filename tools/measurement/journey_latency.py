#!/usr/bin/env python3
"""구매 업무 완료 시간(결정 204 A)을 DB 시각으로 잰다. 업무 데이터는 읽기만 한다.

- 주문 생성 → 구매 근거: ``stove_order.purchase_order.created_at`` → ``stove_license.product_license.received_at``
- 결제 확정 → 구매 근거: ``purchase_order.result_occurred_at``(Payment가 결제를 확정한 시각) → ``product_license.received_at``

주문 표는 ``purchase_order``(Order V10), 구매 근거 표는 ``product_license``(License V5에서 ``license_purchase``를 합침)다.
옛 표 ``orders``·``license_purchase``는 비어 있다. 두 ``order_no``는 모두 ascii_bin이다.

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
import re
import sys

import broker_latency


def purchase_sql(since, until):
    for value in (since, until):
        if not broker_latency._DATETIME.match(value):
            raise ValueError("시각은 'YYYY-MM-DD HH:MM:SS[.ffffff]' 형식이어야 한다: " + value)
    return ("SELECT o.order_no,"
            " TIMESTAMPDIFF(MICROSECOND, o.created_at, lp.received_at),"
            " TIMESTAMPDIFF(MICROSECOND, o.result_occurred_at, lp.received_at)"
            " FROM stove_order.purchase_order o JOIN stove_license.product_license lp"
            " ON lp.order_no = o.order_no"
            f" WHERE o.created_at >= '{since}' AND o.created_at < '{until}'"
            "\nUNION ALL\n"
            "SELECT o.order_no, NULL, NULL FROM stove_order.purchase_order o"
            f" WHERE o.created_at >= '{since}' AND o.created_at < '{until}'"
            " AND NOT EXISTS (SELECT 1 FROM stove_license.product_license lp"
            " WHERE lp.order_no = o.order_no)")


_ORDER_NO = re.compile(r"^[A-Za-z0-9-]{1,40}$")
_JOIN = ("FROM stove_order.purchase_order o JOIN stove_license.product_license lp"
         " ON lp.order_no = o.order_no")


def order_numbers(evidence):
    """부하 드라이버 결과(``MessagingLoadTest``)에서 접수된(200) 주문번호만 꺼낸다."""
    return [order["orderNo"] for order in evidence.get("orders", [])
            if order.get("status") == 200 and order.get("orderNo")]


def _in_list(numbers):
    for number in numbers:
        if not _ORDER_NO.match(number):
            raise ValueError("주문번호 형식이 아니다: " + number)
    return "(" + ",".join("'" + number + "'" for number in numbers) + ")"


def purchase_sql_for_orders(numbers):
    """시각 창 대신 주문번호로 거른다. DB 시간대를 추측하지 않아도 된다."""
    in_list = _in_list(numbers)
    return ("SELECT o.order_no,"
            " TIMESTAMPDIFF(MICROSECOND, o.created_at, lp.received_at),"
            " TIMESTAMPDIFF(MICROSECOND, o.result_occurred_at, lp.received_at) "
            + _JOIN + f" WHERE o.order_no IN {in_list}"
            "\nUNION ALL\n"
            f"SELECT o.order_no, NULL, NULL FROM stove_order.purchase_order o WHERE o.order_no IN {in_list}"
            " AND NOT EXISTS (SELECT 1 FROM stove_license.product_license lp"
            " WHERE lp.order_no = o.order_no)")


def window_sql(numbers):
    """이 주문들의 DB 시각 범위(첫 주문 생성 ~ 마지막 구매 근거). 브로커 구간 지연의 측정 창으로 쓴다."""
    return (f"SELECT MIN(o.created_at), MAX(lp.received_at) " + _JOIN
            + f" WHERE o.order_no IN {_in_list(numbers)}")


def diagnosis_sql(numbers):
    """주문번호가 DB와 맞는지 확인한다: 전체 주문 수, 이 목록과 맞는 주문 수, 맞는 구매 근거 수, 표본 주문번호."""
    in_list = _in_list(numbers)
    return ("SELECT (SELECT COUNT(*) FROM stove_order.purchase_order),"
            f" (SELECT COUNT(*) FROM stove_order.purchase_order o WHERE o.order_no IN {in_list}),"
            f" (SELECT COUNT(*) FROM stove_license.product_license lp WHERE lp.order_no IN {in_list}),"
            " (SELECT GROUP_CONCAT(order_no) FROM (SELECT order_no FROM stove_order.purchase_order ORDER BY id DESC LIMIT 2) s)")


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
    parser.add_argument("--evidence", help="부하 드라이버 결과 JSON. 주면 그 주문번호로 거르고 측정 창을 DB에서 뽑는다")
    parser.add_argument("--since", help="측정 창 시작(DB 시각, 주문 생성 기준). --evidence가 없을 때")
    parser.add_argument("--until", help="측정 창 끝(포함하지 않음). --evidence가 없을 때")
    parser.add_argument("--out")
    args = parser.parse_args()
    password = os.environ.get("MYSQL_ROOT_PASSWORD", "root1234")
    window = {"since": args.since, "until": args.until}
    if args.evidence:
        with open(args.evidence, encoding="utf-8") as handle:
            numbers = order_numbers(json.load(handle))
        sql = purchase_sql_for_orders(numbers)
        bounds = broker_latency._mysql(args.container, password, window_sql(numbers)).split("\t")
        window = {"since": bounds[0].strip(), "until": bounds[1].strip(), "orders": len(numbers)}
        counts = broker_latency._mysql(args.container, password, diagnosis_sql(numbers)).strip().split("\t")
        window["diagnosis"] = {"orders_in_db": counts[0], "matched_orders": counts[1],
                               "matched_purchases": counts[2], "latest_order_numbers": counts[3],
                               "evidence_sample": numbers[:2]}
        if window["since"] == "NULL":
            sys.stderr.write("주문번호로 찾은 구매 근거가 없다: " + json.dumps(window, ensure_ascii=False) + "\n")
            sys.exit(2)
    elif args.since and args.until:
        sql = purchase_sql(args.since, args.until)
    else:
        parser.error("--evidence 또는 --since/--until이 필요하다")
    output = broker_latency._mysql(args.container, password, sql)
    rows = [tuple(line.split("\t")) for line in output.splitlines() if line.strip()]
    text = json.dumps({"window": window, "purchase": summarize(rows)}, ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    else:
        sys.stdout.write(text + "\n")


if __name__ == "__main__":
    main()
