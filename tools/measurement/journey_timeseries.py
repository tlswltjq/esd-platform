#!/usr/bin/env python3
"""층 1 E2E 회차의 Offered·Completed·Outstanding 10초 시계열(결정 230·231, E13 지표 정의). 업무 데이터는 읽기만 한다.

요청마다 시작·끝 시각을 한 시간축(epoch µs)에 놓는다.
- 시작(Offered): 부하 드라이버가 실제로 보낸 시각 ``requestedEpochUs``(드라이버 벽시계).
- 끝: HTTP 접수 실패면 응답 시각 ``respondedEpochUs``(거절), 접수된 주문이면 구매 근거 ``product_license.received_at``(완료)
  또는 결제 실패로 끝난 주문의 ``purchase_order.updated_at``(실패). 어느 것도 없으면 미해소다.
- DB 시각은 앱이 ``Instant``를 UTC로 저장하므로(``serverTimezone=UTC``) 세션 시간대를 UTC로 두고 ``UNIX_TIMESTAMP``로 epoch로 바꾼다.
  드라이버와 MySQL 컨테이너는 같은 호스트 시계를 쓴다. 이 가정이 틀리면 완료가 요청보다 앞서는 주문이 생긴다 — ``clock_check``에 센다.

Outstanding(t) = t까지 보낸 요청 − t까지 끝난 요청. 측정 창은 드라이버가 예정 시각으로 고정한 ``measuredWindow``다.
완료 처리량 = 창 안에 완료된 주문 수 / 창 길이, 유입 = 창 안에 보낸 측정 요청 수 / 창 길이(결정 231 처리량 조건).
측정 창 안 완료와 그 뒤 최종 완료를 나눠 적는다. 미해소가 남으면 ``--wait-secs``(결정 231: 부하 종료 뒤 600초)까지 기다린다.

    python3 -B tools/measurement/journey_timeseries.py --container <mysql> --evidence runs/x/load.json \\
        --wait-secs 600 --out runs/x/timeseries.json
"""
import argparse
import json
import os
import re
import sys
import time

import broker_latency

BUCKET_US = 10_000_000
_ORDER_NO = re.compile(r"^[A-Za-z0-9-]{1,40}$")


def outcome_sql(numbers):
    """주문번호마다 (주문번호, 상태, 구매 근거 epoch µs, 주문 갱신 epoch µs). 세션 시간대를 UTC로 둔다."""
    for number in numbers:
        if not _ORDER_NO.match(number):
            raise ValueError("주문번호 형식이 아니다: " + number)
    in_list = "(" + ",".join("'" + number + "'" for number in numbers) + ")"
    return ("SET time_zone = '+00:00';\n"
            "SELECT o.order_no, o.status,"
            " CAST(UNIX_TIMESTAMP(lp.received_at) * 1000000 AS SIGNED),"
            " CAST(UNIX_TIMESTAMP(o.updated_at) * 1000000 AS SIGNED)"
            " FROM stove_order.purchase_order o LEFT JOIN stove_license.product_license lp ON lp.order_no = o.order_no"
            " WHERE o.order_no IN " + in_list)


def parse_outcomes(text):
    outcomes = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        order_no, status, received, updated = line.split("\t")
        outcomes[order_no] = {"status": status, "received_us": None if received == "NULL" else int(received),
                              "updated_us": None if updated == "NULL" else int(updated)}
    return outcomes


def requests(evidence, outcomes):
    """요청마다 (시작, 끝, 종류, 측정 여부). 종류: completed·failed·rejected·unresolved."""
    rows = []
    for order in evidence.get("orders", []):
        start = order.get("requestedEpochUs")
        if start is None:
            continue
        measured = not order.get("warmup")
        if order.get("status") != 200:
            rows.append((start, order.get("respondedEpochUs", start), "rejected", measured))
            continue
        outcome = outcomes.get(order.get("orderNo"))
        if outcome and outcome["received_us"] is not None:
            rows.append((start, outcome["received_us"], "completed", measured))
        elif outcome and outcome["status"] == "FAILED" and outcome["updated_us"] is not None:
            rows.append((start, outcome["updated_us"], "failed", measured))
        else:
            rows.append((start, None, "unresolved", measured))
    return rows


def summarize(evidence, rows):
    window = evidence.get("measuredWindow") or {}
    start, end = window.get("startEpochUs"), window.get("endEpochUs")
    if not rows:
        return {"window": window, "note": "요청 기록이 없다"}
    finished = [row[1] for row in rows if row[1] is not None]
    # 시각이 뒤집힌 행(끝 < 시작)이 있어도 칸 번호가 음수가 되지 않게 모든 시각 중 가장 이른 것을 원점으로 둔다(PR #254 리뷰 R4).
    origin = min([row[0] for row in rows] + finished)
    last = max([row[0] for row in rows] + finished)
    buckets = (last - origin) // BUCKET_US + 1
    series = {name: [0] * buckets for name in ("offered", "completed", "failed", "rejected")}
    for begin, finish, kind, _measured in rows:
        series["offered"][(begin - origin) // BUCKET_US] += 1
        if finish is not None:
            series[kind][(finish - origin) // BUCKET_US] += 1
    outstanding = []
    sent = done = 0
    for index in range(buckets):
        sent += series["offered"][index]
        done += series["completed"][index] + series["failed"][index] + series["rejected"][index]
        outstanding.append(sent - done)
    series["outstanding"] = outstanding

    measured = [row for row in rows if row[3]]
    load_end = max(row[0] for row in rows)
    unresolved = sum(1 for row in rows if row[1] is None)
    result = {
        "origin_us": origin, "bucket_us": BUCKET_US, "window": window,
        "measure_from_bucket": None if start is None else (start - origin) // BUCKET_US,
        "measure_to_bucket": None if end is None else (end - 1 - origin) // BUCKET_US,
        "series": series,
        "totals": {kind: sum(1 for row in rows if row[2] == kind) for kind in ("completed", "failed", "rejected", "unresolved")},
        "load_end_us": load_end,
        "unresolved": unresolved,
        "last_finish_us": max(finished) if finished else None,
        # 업무만 본 해소: 마지막 요청 → 마지막 요청이 끝난 시각(Outstanding 0). 소비 지연까지 0인 E13 Backlog 해소 시간은
        # 측정 흐름이 지연 표본과 합쳐 ``backlog_drain_seconds``로 낸다. 미해소가 남으면 None.
        "outstanding_drain_seconds": None if unresolved else round((max(finished) - load_end) / 1e6, 3) if finished else None,
        "clock_check": {"finish_before_start": sum(1 for row in rows if row[1] is not None and row[1] < row[0])},
    }
    if start is not None and end is not None and end > start:
        seconds = (end - start) / 1e6
        offered = sum(1 for row in measured if start <= row[0] < end)
        completed_in = sum(1 for row in rows if row[2] == "completed" and start <= row[1] < end)
        result["rates"] = {
            "offered_per_s": offered / seconds,
            "completed_per_s": completed_in / seconds,
            # 결정 231 처리량 조건: 정상 상태 완료 처리량 / 실제 유입량 ≥ 99%.
            "completed_over_offered": (completed_in / offered) if offered else None,
        }
        measured_completed = [row for row in measured if row[2] == "completed"]
        result["measured_orders"] = {
            "requested": len(measured),
            "completed_in_window": sum(1 for row in measured_completed if row[1] < end),
            "completed_after_window": sum(1 for row in measured_completed if row[1] >= end),
            "failed": sum(1 for row in measured if row[2] == "failed"),
            "rejected": sum(1 for row in measured if row[2] == "rejected"),
            "unresolved": sum(1 for row in measured if row[2] == "unresolved"),
        }
    return result


def collect(container, password, evidence):
    numbers = [order["orderNo"] for order in evidence.get("orders", []) if order.get("status") == 200 and order.get("orderNo")]
    outcomes = parse_outcomes(broker_latency._mysql(container, password, outcome_sql(numbers))) if numbers else {}
    return summarize(evidence, requests(evidence, outcomes))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--container", required=True)
    parser.add_argument("--evidence", required=True, help="부하 드라이버 결과(MessagingLoadTest load.json)")
    parser.add_argument("--wait-secs", type=int, default=0, help="미해소가 남으면 부하 종료 뒤 이만큼까지(10초 간격) 다시 읽는다")
    parser.add_argument("--load-ended-us", type=int, default=None,
                        help="기다림의 기준인 부하 종료 시각(epoch µs). 없으면 마지막 요청 시각")
    parser.add_argument("--out")
    args = parser.parse_args()
    password = os.environ.get("MYSQL_ROOT_PASSWORD", "root1234")
    with open(args.evidence, encoding="utf-8") as handle:
        evidence = json.load(handle)
    result = collect(args.container, password, evidence)
    load_ended = args.load_ended_us or result.get("load_end_us") or time.time_ns() // 1000
    # 기한은 부하 종료 시각부터다. 벽시계 차이로 남은 시간을 단조 시계에 옮긴다.
    deadline = time.monotonic() + max(0.0, args.wait_secs - (time.time_ns() // 1000 - load_ended) / 1e6)
    while result.get("unresolved") and time.monotonic() < deadline:
        time.sleep(min(10.0, max(0.0, deadline - time.monotonic())))
        result = collect(args.container, password, evidence)
    result["observation"] = {"wait_secs": args.wait_secs, "timed_out": bool(result.get("unresolved"))}
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    else:
        sys.stdout.write(text + "\n")


if __name__ == "__main__":
    main()
