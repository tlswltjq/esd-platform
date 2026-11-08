#!/usr/bin/env python3
"""G1 combinations past the 30-minute order deadline on the real stack with the PortOne fake (decision 120).

All orders are created at the start and driven into their state; one wait past the latest deadline covers them all.
  e1 admitted inside the deadline, PG PAY_PENDING, settles FAILED after the deadline -> Order EXPIRED (Order R3.10)
     and Payment EXPIRED (Payment R11.6); the buyer is sent to a new order (R3.7)
  e2 admitted inside the deadline, PG PAY_PENDING, settles PAID after the deadline -> the original order PAID with
     its original amount (Order R3.6) -> License granted and registered
  e0 control: a PENDING order without an approval expires on its own (Order R3.5/R3.12, Payment R11.4), so the
     "admitted orders did not expire" checks are not vacuous

Not here: PAY_PENDING before our admission past the deadline (defect D-044, fix policy undecided) and the 3-hour
confirm-window combinations (decision 124). The Order deadline (30 min) and the clock are constants by design
(decision 111), so this journey really waits; it runs as its own nightly job, never on pull requests.
"""
from datetime import datetime
from pathlib import Path
import os, sys, time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/verification"))
from support.fake_portone_stack import FakePortOneStack  # noqa: E402

GRACE_SECONDS = int(os.environ.get("EXPIRY_JOURNEYS_GRACE_SECONDS", "60"))


def error_code(value):
    return (value.get("error") or {}).get("code")


def expires_at(stack, order_no):
    return datetime.fromisoformat(stack.buyer.data("/api/v1/orders/" + order_no)["expiresAt"].replace("Z", "+00:00")).timestamp()


def outbox(schema, event_type, order_no):
    return ("SELECT COUNT(*) FROM stove_" + schema + ".outbox_event WHERE event_type='" + event_type
            + "' AND aggregate_id='" + order_no + "'")


def journey(stack):
    record, check, wait_for, buyer = stack.record, stack.check, stack.wait_for, None
    subject, e1 = stack.prepare()
    buyer = stack.buyer
    e2 = stack.create_order("e2-late-paid")
    e0 = stack.create_order("e0-natural-expiry")
    with stack.lock:
        stack.state["orders"][e1]["name"] = "e1-late-failed"
        stack.save_state()

    stack.set_step("admit", "기한 안에 두 주문의 승인을 접수하고 PG를 PAY_PENDING으로 둡니다.", order_no=e1)
    admitted = {}
    for order_no in (e1, e2):
        admitted[order_no] = stack.admit_pay_pending(order_no)
    stack.wait_payment_pending(e0)
    record("e start: e1|e2 order|payment|approval, e0 order|payment",
           "PAYMENT_PROCESSING|PENDING|ADMITTED, PENDING|PENDING")

    deadline = max(expires_at(stack, order_no) for order_no in (e0, e1, e2))
    stack.set_step("wait", "가장 늦은 주문 기한 뒤 " + str(GRACE_SECONDS) + "초까지 기다립니다.", order_no=e1)
    stack.log("Waiting " + str(round(deadline + GRACE_SECONDS - time.time())) + " seconds past the latest order deadline")
    while time.time() < deadline + GRACE_SECONDS:
        # Admitted progress never expires on time alone (Order R3.6); check once a minute, not only at the end.
        for order_no, (_, _, approval_id) in admitted.items():
            assert stack.statuses(order_no, approval_id) == ("PAYMENT_PROCESSING", "PENDING", "ADMITTED"), (order_no, stack.statuses(order_no, approval_id))
        time.sleep(min(60, max(1, deadline + GRACE_SECONDS - time.time())))
    stack.log("Past the deadline by " + str(round(time.time() - deadline)) + " seconds")

    wait_for(lambda: stack.statuses(e0) == ("EXPIRED", "EXPIRED"), "e0 natural expiry", 120, 2)
    check("e0 OrderExpired outbox", outbox("order", "OrderExpired", e0), 1)
    record("e0 control past deadline: order|payment|OrderExpired", "EXPIRED|EXPIRED|1")
    for order_no, (payment_id, _, approval_id) in admitted.items():
        assert stack.statuses(order_no, approval_id) == ("PAYMENT_PROCESSING", "PENDING", "ADMITTED"), (order_no, stack.statuses(order_no, approval_id))
        assert stack.confirm_statuses(payment_id) == [200], stack.confirm_statuses(payment_id)
        assert stack.fake.view_history(payment_id)[-1]["status"] == "PAY_PENDING"
        check("e admitted not expired " + order_no, outbox("order", "OrderExpired", order_no), 0)
        status, value = buyer.call("GET", "/api/v1/payments/" + order_no + "/checkout")
        assert (status, error_code(value)) == (409, "APPROVAL_IN_PROGRESS"), (status, value)
    record("e past deadline: e1|e2 order|payment|approval|re-POST|OrderExpired|checkout",
           "PAYMENT_PROCESSING|PENDING|ADMITTED|0|0|409 APPROVAL_IN_PROGRESS")

    # ---- e1 late FAILED -> EXPIRED ----------------------------------------------------------------------
    payment1, sdk1, approval1 = admitted[e1]
    stack.set_step("late-failed", "기한 뒤 PG가 FAILED로 끝나면 주문이 EXPIRED가 되는지 확인합니다.", order_no=e1)
    stack.console(payment1, "settle", {"status": "FAILED"})
    wait_for(lambda: stack.statuses(e1, approval1) == ("EXPIRED", "EXPIRED", "FAILED"), "e1 late FAILED -> EXPIRED", 180, 2)
    check("e1 Order admission closed as EXPIRED",
          "SELECT CONCAT_WS('|',COUNT(*),MAX(closed_order_status)) FROM stove_order.order_payment_admission WHERE order_no='" + e1
          + "' AND failed_supplier_attempt_id='" + sdk1["txId"] + "'", "1|EXPIRED")
    check("e1 OrderExpired outbox", outbox("order", "OrderExpired", e1), 1)
    check("e1 no OrderPaid/OrderPaymentFailed/License",
          "SELECT (" + outbox("order", "OrderPaid", e1) + ")+(" + outbox("payment", "OrderPaymentFailed", e1) + ")"
          "+(SELECT COUNT(*) FROM stove_license.product_license WHERE order_no='" + e1 + "')", 0)
    status, value = buyer.call("GET", "/api/v1/payments/" + e1 + "/checkout")
    assert status == 409, (status, value)
    record("e1 late FAILED: order|payment|approval|admission closed|OrderExpired|checkout", "EXPIRED|EXPIRED|FAILED|EXPIRED|1|409")

    # ---- e2 late PAID -> original order PAID ------------------------------------------------------------
    payment2, _, approval2 = admitted[e2]
    stack.set_step("late-paid", "기한 안에 접수된 PAY_PENDING이 기한 뒤 PAID가 되면 원래 주문이 PAID가 되는지 확인합니다.", order_no=e2)
    stack.console(payment2, "settle", {"status": "PAID"})
    wait_for(lambda: stack.statuses(e2, approval2) == ("PAID", "PAID", "SUCCEEDED"), "e2 late PAID", 180, 2)
    price = stack.info["product"]["price"]
    order, payment = buyer.data("/api/v1/orders/" + e2), buyer.data("/api/v1/payments/" + e2)
    assert (order["amount"], payment["amount"], payment["paymentId"]) == (price, price, payment2), (order, payment)
    check("e2 OrderPaymentCompleted outbox", outbox("payment", "OrderPaymentCompleted", e2), 1)
    check("e2 OrderPaid outbox", outbox("order", "OrderPaid", e2), 1)
    check("e2 no OrderExpired", outbox("order", "OrderExpired", e2), 0)
    license_id = stack.register(e2)
    check("e2 one ACTIVE registration", "SELECT COUNT(*) FROM stove_license.license_registration WHERE status='ACTIVE' AND buyer_subject='" + subject + "'", 1)
    assert stack.confirm_statuses(payment2) == [200]
    record("e2 late PAID: order|payment|approval|amount|License", "PAID|PAID|SUCCEEDED|" + str(price) + "|ACTIVE")

    assert all(r["caller"] == "payment" for r in stack.fake.requests if r["path"].endswith(("/confirm", "/cancel")))
    assert not any(r["path"].endswith("/cancel") for r in stack.fake.requests), "no API cancel in this journey"
    record("e fake callers: confirm=payment, API cancel", 0)
    with stack.lock:
        stack.state["summary"] = {"lateFailed": e1, "latePaid": e2, "naturalExpiry": e0, "license": license_id,
                                  "pastDeadlineSeconds": round(time.time() - deadline)}
        stack.save_state()


if __name__ == "__main__":
    FakePortOneStack(REPO, "verify-expiry-journeys", offset_env="EXPIRY_JOURNEYS_TEST_PORT_OFFSET", prefix="expiry-journeys-").run(
        journey, "admitted PAY_PENDING past the deadline -> late FAILED EXPIRED, late PAID PAID; control order expired")
