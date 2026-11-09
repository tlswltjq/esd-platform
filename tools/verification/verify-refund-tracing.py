#!/usr/bin/env python3
"""Tempo trace continuity of the new refund flow on the local PortOne fake (decisions 17, 28, 111, 119).

Three automatic refunds run one after another on an isolated stack with its own Tempo and sampling 1:
  normal         cancel answered at once (Kafka listener thread)
  lost-response  cancel applied but its answer lost, first lookup 503 -> durable lookup worker confirms
  pg-error       cancel answered 503 without cancelling -> durable retry worker sends again

For every refund the tool reads the buyer's refund trace (Gateway X-Correlation-Id), every refund
Outbox row of Order/License/Payment with its stored traceparent, Payment's saved refund parent, and the
Tempo spans of each trace it finds. A segment is connected when its Outbox row (or span) belongs to the
refund trace. The refund trace itself must be complete evidence (support/refund_tracing.py): Tempo lookup
succeeds with spans, exactly one root, gateway/order/license/payment/settlement, no missing parent, and the
scenario's PG spans with worker lookups/resends under payment.process. Evidence: runs/refund-tracing-*/refund-tracing.json. The tool fails while any segment is
broken, after the business outcome itself has been checked.
"""
from pathlib import Path
import json, sys, time, urllib.parse, urllib.request

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/verification"))
from support.fake_portone_stack import FakePortOneStack  # noqa: E402
from support.refund_tracing import TraceUnavailable, await_trace, describe, fetch_trace, observation_window, pg_call  # noqa: E402

REFUND_EVENTS = {
    "stove_order": ["LicenseSuspensionRequested", "RefundExecutionRequested", "PurchaseRefunded"],
    "stove_license": ["LicenseSuspended", "LicenseRevoked"],
    "stove_payment": ["RefundExecutionPrepared", "RefundPaymentConfirmed", "RefundExecutionSucceeded"],
}
SCENARIOS = [("normal", None), ("lost-response", {"outcome": "LOST_RESPONSE", "failLookups": 1}),
             ("pg-error", {"outcome": "PG_ERROR", "failLookups": 0})]


def tempo_spans(stack, trace_id):
    """Lenient lookup for diagnostics of other traces only; the refund trace itself uses await_trace."""
    try:
        return fetch_trace(stack.tempo_url, trace_id)
    except TraceUnavailable:
        return []


def pg_call_traces(stack, payment_id, started):
    """Every trace holding a Payment HTTP client span to the fake for this paymentId (TraceQL search)."""
    query = '{ resource.service.name = "payment" && span.http.url =~ ".*' + payment_id + '.*" }'
    target = (stack.tempo_url + "/api/search?" + urllib.parse.urlencode(
        dict(q=query, start=int(started) - 60, end=int(time.time()) + 60, limit=100, spss=50)))
    with urllib.request.urlopen(target, timeout=10) as response:
        found = json.load(response).get("traces", [])
    result = []
    for trace in found:
        spans = tempo_spans(stack, trace["traceID"].rjust(32, "0"))
        calls = [s["name"] + " " + urllib.parse.urlsplit(s["url"]).path for s in spans
                 if pg_call(s, payment_id) and s["start"] >= started]
        if not calls:
            continue  # purchase-phase calls only
        result.append(dict(traceId=trace["traceID"].rjust(32, "0"), root=trace.get("rootServiceName", "?") + ":" + trace.get("rootTraceName", "?"),
                           calls=sorted(calls), trace=describe(spans)))
    return result


def trace_of(parent):
    return parent.split("-")[1] if parent and parent.count("-") == 3 else None


def outbox_rows(stack, order_no):
    rows = []
    for schema, types in REFUND_EVENTS.items():
        names = ",".join("'" + t + "'" for t in types)
        result = stack.sql("SELECT event_type,event_id,COALESCE(trace_parent,''),status FROM " + schema + ".outbox_event "
                           "WHERE event_type IN (" + names + ") AND payload LIKE '%" + order_no + "%' ORDER BY id")
        for line in filter(None, result.splitlines()):
            event_type, event_id, parent, status = line.split("\t")
            rows.append(dict(schema=schema, eventType=event_type, eventId=event_id, traceParent=parent or None, status=status))
    return rows


def journey(stack):
    _, first = stack.prepare()
    buyer, fake = stack.buyer, stack.fake
    evidence = {"tempo": stack.tempo_url, "scenarios": {}}
    output = stack.directory / "refund-tracing.json"
    broken = []
    order_no = first
    for index, (name, fault) in enumerate(SCENARIOS):
        if index:
            order_no = stack.create_order(name)
        stack.pay(name, order_no, name + " 구매")
        # Refund after Order confirmed the grant, so the refund request itself starts the suspension
        # (before that, the later LicenseGranted consumption continues the refund in the purchase trace).
        stack.check(name + " license grant confirmed", "SELECT COUNT(*) FROM stove_order.order_license_grant WHERE order_no='" + order_no + "'", 1, 120)
        started = time.time()
        payment_id = buyer.data("/api/v1/payments/" + order_no)["paymentId"]
        if fault:
            stack.console(payment_id, "arm-cancel", fault)
        stack.set_step(name + "-refund", name + " 환불을 요청하고 Tempo 연결을 확인합니다.")
        status, headers, raw = buyer.request("POST", "/api/v1/orders/" + order_no + "/refund")
        assert status in {200, 202}, "refund HTTP " + str(status) + " " + raw.decode()
        refund_trace = headers.get("X-Correlation-Id")
        assert refund_trace and len(refund_trace) == 32, "refund response has no trace id"
        stack.wait_for(lambda: buyer.data("/api/v1/orders/" + order_no + "/refund")["status"] == "COMPLETED",
                       name + " refund COMPLETED", 420, 2)
        stack.check(name + " license refunded", "SELECT status FROM stove_license.product_license WHERE order_no='" + order_no + "'", "REFUNDED")
        stack.check(name + " settlement refund", "SELECT COUNT(*) FROM stove_settlement.settlement_refund WHERE order_no='" + order_no + "'", 1, 120)
        cancels = [r["status"] for r in stack.requests_for(payment_id, "/cancel")]
        expected = {"normal": [200], "lost-response": ["LOST"], "pg-error": [503, 200]}[name]
        assert cancels == expected, name + " cancel statuses " + str(cancels)
        stack.record(name + " PG cancel statuses", cancels)
        attempt = stack.sql("SELECT attempt_id FROM stove_payment.refund_execution WHERE payment_id='" + payment_id + "'")
        saved = stack.sql("SELECT trace_parent FROM stove_payment.refund_execution_trace WHERE attempt_id='" + attempt + "'")
        source = stack.sql("SELECT COALESCE(refund_confirmation_source,'') FROM stove_payment.refund_execution WHERE attempt_id='" + attempt + "'")
        # Business completion: every refund Outbox row of this order is SENT (consumers then finish).
        stack.wait_for(lambda: outbox_rows(stack, order_no) and all(r["status"] == "SENT" for r in outbox_rows(stack, order_no)),
                       name + " refund Outbox SENT", 120, 1)
        done = time.monotonic()
        # Watch the trace for observation_window() seconds after completion, then require a complete, stable
        # snapshot (one root, all services, no missing parent, the scenario's PG spans).
        try:
            main = await_trace(lambda trace_id: fetch_trace(stack.tempo_url, trace_id), refund_trace, name, payment_id,
                               seconds=120, quiet_until=done + observation_window())
            tempo_problems = []
        except AssertionError as incomplete:
            main, tempo_problems = tempo_spans(stack, refund_trace), [str(incomplete)]
            broken.append(name + ": " + str(incomplete))
        rows = outbox_rows(stack, order_no)
        segments = []
        for row in rows:
            row_trace = trace_of(row["traceParent"])
            connected = row_trace == refund_trace
            segment = dict(row, traceId=row_trace, connected=connected)
            if not connected and row_trace:
                segment["ownTrace"] = describe(tempo_spans(stack, row_trace))
            segments.append(segment)
            if not connected:
                broken.append(name + ": " + row["schema"] + "." + row["eventType"] + " traceparent " + str(row["traceParent"]))
        saved_trace = trace_of(saved)
        if saved_trace != refund_trace:
            broken.append(name + ": refund_execution_trace " + saved)
        cancel_spans = [s for s in main if pg_call(s, payment_id) == "cancel"]
        lookup_spans = [s for s in main if pg_call(s, payment_id) == "lookup"]
        pg_traces = pg_call_traces(stack, payment_id, started)
        for trace in pg_traces:
            if trace["traceId"] != refund_trace:  # calls already hold only this payment's exact PG paths
                broken.append(name + ": Payment PG calls in another trace " + trace["traceId"] + " root " + trace["root"] + " " + str(trace["calls"]))
        summary = describe(main)
        evidence["scenarios"][name] = dict(orderNo=order_no, paymentId=payment_id, attemptId=attempt, refundTrace=refund_trace,
                                           refundConfirmationSource=source, savedRefundParent=saved,
                                           savedParentConnected=saved_trace == refund_trace, tempo=summary, tempoProblems=tempo_problems,
                                           pgCancelSpans=len(cancel_spans), pgLookupSpans=len(lookup_spans),
                                           pgRequests=[dict(method=r["method"], path=r["path"].split("?")[0], status=r["status"])
                                                       for r in fake.requests if payment_id in r["path"]],
                                           pgCallTraces=pg_traces, outbox=segments, spans=main)
        output.write_text(json.dumps(evidence, indent=2, ensure_ascii=False))
        stack.record(name + " refund trace", refund_trace + " spans=" + str(summary["spanCount"]) + " services=" + ",".join(summary["services"]))
        for segment in segments:
            stack.log(("CONNECTED: " if segment["connected"] else "BROKEN: ") + name + " " + segment["schema"] + "."
                      + segment["eventType"] + " trace=" + str(segment["traceId"]))
    evidence["broken"] = broken
    output.write_text(json.dumps(evidence, indent=2, ensure_ascii=False))
    assert not broken, "refund trace broken at " + str(len(broken)) + " segment(s): " + "; ".join(broken)


if __name__ == "__main__":
    FakePortOneStack(REPO, "verify-refund-tracing", offset_env="REFUND_TRACING_TEST_PORT_OFFSET", prefix="refund-tracing-",
                     tracing=True).run(journey, "local PortOne fake refunds keep one Tempo trace (normal, lost response, PG error)")
