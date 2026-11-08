#!/usr/bin/env python3
"""G1 combinations past the 30-minute order deadline on the real stack with the PortOne fake (decision 120).

All orders are created at the start and driven into their state; one wait past the latest deadline covers them all.
  e1 admitted inside the deadline, PG PAY_PENDING, settles FAILED after the deadline -> Order EXPIRED (Order R3.10)
     and Payment EXPIRED (Payment R11.6); the buyer is sent to a new order (R3.7)
  e2 admitted inside the deadline, PG PAY_PENDING, settles PAID after the deadline -> the original order PAID with
     its original amount (Order R3.6) -> License granted and registered
  e0 control: a PENDING order without an approval expires on its own (Order R3.5/R3.12, Payment R11.4), so the
     "admitted orders did not expire" checks are not vacuous
  p1 PG PAY_PENDING before our admission (no confirm) past the deadline (D-044, decision 126): the order expires on its own,
     the approval looks up the PG once and is held as PG_PENDING_AT_ORDER_EXPIRY (Payment R11.5, customer notice
     CHARGE_UNCONFIRMED) instead of closing; the hold keeps looking up, a late PAID becomes PAID_WITHOUT_ADMISSION
     (CHARGED), closure is refused while PAID, and after a console full cancel the ADMIN closure ends it with the payment
     EXPIRED and no failure event (Payment R13.5, Order R4.8); the buyer is sent to a new order

Not here: the 3-hour confirm-window combinations (decision 124, PaymentApprovalIntegrationTest). The Order deadline (30 min) and the clock are constants by design
(decision 111), so this journey really waits; it runs as its own nightly job, never on pull requests.
"""
from datetime import datetime
from pathlib import Path
import os, sys, time, uuid

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/verification"))
from support.fake_portone_stack import FakePortOneStack  # noqa: E402

GRACE_SECONDS = int(os.environ.get("EXPIRY_JOURNEYS_GRACE_SECONDS", "60"))
GAUGE = "stove_payment_approval_review_required"


def error_code(value):
    return (value.get("error") or {}).get("code")


def expires_at(stack, order_no):
    return datetime.fromisoformat(stack.buyer.data("/api/v1/orders/" + order_no)["expiresAt"].replace("Z", "+00:00")).timestamp()


def outbox(schema, event_type, order_no):
    return ("SELECT COUNT(*) FROM stove_" + schema + ".outbox_event WHERE event_type='" + event_type
            + "' AND aggregate_id='" + order_no + "'")


def close_review(stack, admin, approval_id, request_id, note):
    """ADMIN closure; retries only while the approval worker holds the lease (R13.3 APPROVAL_CLOSURE_BUSY)."""
    result = {}

    def attempt():
        status, value = admin.call("POST", "/api/v1/admin/approval-reviews/" + approval_id + "/closure",
                                   {"requestId": request_id, "note": note})
        result.update(status=status, value=value)
        return not (status == 409 and error_code(value) == "APPROVAL_CLOSURE_BUSY")
    stack.wait_for(attempt, "closure answered without lease contention " + request_id, 60, 1)
    return result["status"], result["value"]


def held_row(admin, reason, approval_id):
    page = admin.data("/api/v1/admin/approval-reviews?reason=" + reason + "&size=50")
    return next((row for row in page["content"] if row["approvalId"] == approval_id), None)


def notice(stack, order_no, approval_id):
    value = stack.buyer.data("/api/v1/payments/" + order_no + "/approvals/" + approval_id)["reviewNotice"]
    return None if value is None else value["code"]


def submit_pending_before_admission(stack, order_no):
    """The PG reaches PAY_PENDING before our admission (no confirm); the approval stays REQUESTED and only looks up."""
    stack.wait_payment_pending(order_no)
    checkout, sdk = stack.open_checkout(order_no)
    payment_id = checkout["paymentId"]
    stack.console(payment_id, "pay-pending")
    approval_id = stack.submit_approval(order_no, sdk)["requestId"]
    stack.wait_for(lambda: stack.lookups(payment_id) >= 2 and stack.statuses(order_no, approval_id) == ("PENDING", "PENDING", "REQUESTED"),
                   "unadmitted PAY_PENDING looked up " + order_no, 120, 2)
    assert stack.confirm_statuses(payment_id) == [], "an unadmitted approval must never be confirmed"
    assert stack.fake.view_history(payment_id)[-1]["status"] == "PAY_PENDING"
    return payment_id, approval_id


def pending_expiry(stack, p1, payment_id, approval_id):
    """p1 (decision 126, D-044): unadmitted PAY_PENDING at the order expiry is held, not closed; then its next actions."""
    buyer, record, check, wait_for = stack.buyer, stack.record, stack.check, stack.wait_for
    admin = stack.admin()
    stack.set_step("pending-expiry", "접수 전 PAY_PENDING 주문이 만료되면 PG를 다시 조회해 보류로 올리는지 확인합니다.", order_no=p1)
    wait_for(lambda: stack.statuses(p1, approval_id) == ("EXPIRED", "PENDING", "REVIEW_REQUIRED"), "p1 held at order expiry", 180, 2)
    check("p1 OrderExpired outbox", outbox("order", "OrderExpired", p1), 1)
    wait_for(lambda: held_row(admin, "PG_PENDING_AT_ORDER_EXPIRY", approval_id) is not None, "p1 ADMIN list", 60, 2)
    row = held_row(admin, "PG_PENDING_AT_ORDER_EXPIRY", approval_id)
    assert (row["reason"], row["admitted"], row["status"], row["orderNo"], row["latestPgState"]) == (
        "PG_PENDING_AT_ORDER_EXPIRY", False, "REVIEW_REQUIRED", p1, "PENDING"), row
    assert notice(stack, p1, approval_id) == "CHARGE_UNCONFIRMED"
    assert buyer.data("/api/v1/payments/" + p1)["reviewNotice"]["code"] == "CHARGE_UNCONFIRMED"
    wait_for(lambda: stack.metric("payment", GAUGE, {"reason": "PG_PENDING_AT_ORDER_EXPIRY"}) == 1.0, "p1 gauge 1", 60, 2)
    status, value = buyer.call("GET", "/api/v1/payments/" + p1 + "/checkout")
    assert status == 409, (status, value)
    assert stack.confirm_statuses(payment_id) == []
    record("p1 at expiry: order|payment|approval|reason|admitted|latestPgState|notice|gauge|checkout|confirm POSTs",
           "EXPIRED|PENDING|REVIEW_REQUIRED|PG_PENDING_AT_ORDER_EXPIRY|False|PENDING|CHARGE_UNCONFIRMED|1|409|0")
    lookups = stack.lookups(payment_id)
    wait_for(lambda: stack.lookups(payment_id) > lookups, "p1 hold keeps looking up the PG", 180, 2)
    record("p1 hold keeps looking up the PG (decision 122)", "yes")

    stack.set_step("pending-expiry-paid", "보류 중 PG가 PAID가 되면 PAID_WITHOUT_ADMISSION 보류로 바뀌는지 확인합니다.", order_no=p1)
    stack.console(payment_id, "settle", {"status": "PAID"})
    wait_for(lambda: (held_row(admin, "PAID_WITHOUT_ADMISSION", approval_id) or {}).get("latestPgState") == "PAID",
             "p1 late PAID -> PAID_WITHOUT_ADMISSION", 300, 2)
    assert held_row(admin, "PG_PENDING_AT_ORDER_EXPIRY", approval_id) is None
    assert notice(stack, p1, approval_id) == "CHARGED"
    assert stack.statuses(p1, approval_id) == ("EXPIRED", "PENDING", "REVIEW_REQUIRED"), stack.statuses(p1, approval_id)
    wait_for(lambda: stack.metric("payment", GAUGE, {"reason": "PAID_WITHOUT_ADMISSION"}) == 1.0
             and stack.metric("payment", GAUGE, {"reason": "PG_PENDING_AT_ORDER_EXPIRY"}) == 0.0, "p1 gauge moved to PAID_WITHOUT_ADMISSION", 60, 2)
    check("p1 no OrderPaid/OrderPaymentCompleted/License",
          "SELECT (" + outbox("order", "OrderPaid", p1) + ")+(" + outbox("payment", "OrderPaymentCompleted", p1) + ")"
          "+(SELECT COUNT(*) FROM stove_license.product_license WHERE order_no='" + p1 + "')", 0)
    status, value = close_review(stack, admin, approval_id, str(uuid.uuid4()), "PG 콘솔 취소 전 종결 시도")
    assert (status, error_code(value)) == (409, "CLOSURE_PG_PAID"), (status, value)
    record("p1 late PAID: order|payment|approval|reason|notice|gauge|OrderPaid/License|closure",
           "EXPIRED|PENDING|REVIEW_REQUIRED|PAID_WITHOUT_ADMISSION|CHARGED|1|0|409 CLOSURE_PG_PAID")

    stack.set_step("pending-expiry-closure", "콘솔 전액 취소 뒤 ADMIN 종결로 끝나고 새 주문 안내가 되는지 확인합니다.", order_no=p1)
    stack.console(payment_id, "console-cancel")
    wait_for(lambda: held_row(admin, "PAID_WITHOUT_ADMISSION", approval_id)["latestPgState"] == "CANCELLED", "p1 observes CANCELLED", 900, 5)
    assert notice(stack, p1, approval_id) == "CHARGE_CANCELLED"
    status, closed = close_review(stack, admin, approval_id, str(uuid.uuid4()), "PG 콘솔 전액 취소 확인 후 종결")
    assert status == 200, (status, closed)
    closed = closed["data"]
    assert (closed["approvalStatus"], closed["paymentStatus"], closed["closure"]["evidenceState"]) == ("FAILED", "EXPIRED", "CANCELLED"), closed
    assert stack.statuses(p1, approval_id) == ("EXPIRED", "EXPIRED", "FAILED"), stack.statuses(p1, approval_id)
    assert notice(stack, p1, approval_id) == "CLOSED_CHARGE_CANCELLED"
    check("p1 no OrderPaymentFailed after unadmitted closure", outbox("payment", "OrderPaymentFailed", p1), 0)
    check("p1 OrderExpired still once", outbox("order", "OrderExpired", p1), 1)
    wait_for(lambda: stack.metric("payment", GAUGE, {"reason": "PAID_WITHOUT_ADMISSION"}) == 0.0, "p1 gauge 0", 60, 2)
    assert held_row(admin, "PAID_WITHOUT_ADMISSION", approval_id) is None
    status, value = buyer.call("GET", "/api/v1/payments/" + p1 + "/checkout")
    assert status == 409, (status, value)
    assert stack.confirm_statuses(payment_id) == [] and [i["status"] for i in stack.fake.view_history(payment_id)] == ["CANCELLED"]
    record("p1 closure: approval|payment|evidence|order|notice|OrderPaymentFailed|gauge|checkout|confirm POSTs",
           "FAILED|EXPIRED|CANCELLED|EXPIRED|CLOSED_CHARGE_CANCELLED|0|0|409|0")
    after = stack.create_order("p1-next-order")
    stack.wait_payment_pending(after)
    record("p1 next action: new order", "PENDING")
    return after


def journey(stack):
    record, check, wait_for, buyer = stack.record, stack.check, stack.wait_for, None
    subject, e1 = stack.prepare()
    buyer = stack.buyer
    e2 = stack.create_order("e2-late-paid")
    e0 = stack.create_order("e0-natural-expiry")
    p1 = stack.create_order("p1-pending-before-admission")
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
    stack.set_step("pending-before-admission", "접수 전에 PG가 PAY_PENDING이 된 주문을 만듭니다.", order_no=p1)
    pending_payment, pending_approval = submit_pending_before_admission(stack, p1)
    record("p1 start: order|payment|approval|confirm POSTs|PG", "PENDING|PENDING|REQUESTED|0|PAY_PENDING")
    p1_expires = expires_at(stack, p1)

    deadline = max(expires_at(stack, order_no) for order_no in (e0, e1, e2, p1))
    stack.set_step("wait", "가장 늦은 주문 기한 뒤 " + str(GRACE_SECONDS) + "초까지 기다립니다.", order_no=e1)
    stack.log("Waiting " + str(round(deadline + GRACE_SECONDS - time.time())) + " seconds past the latest order deadline")
    while time.time() < deadline + GRACE_SECONDS:
        # Admitted progress never expires on time alone (Order R3.6); check once a minute, not only at the end.
        for order_no, (_, _, approval_id) in admitted.items():
            assert stack.statuses(order_no, approval_id) == ("PAYMENT_PROCESSING", "PENDING", "ADMITTED"), (order_no, stack.statuses(order_no, approval_id))
        if time.time() < p1_expires - 5:
            # Unadmitted PAY_PENDING is only looked up before the deadline: never confirmed, never held.
            assert stack.statuses(p1, pending_approval) == ("PENDING", "PENDING", "REQUESTED"), stack.statuses(p1, pending_approval)
            assert stack.confirm_statuses(pending_payment) == []
        time.sleep(min(60, max(1, deadline + GRACE_SECONDS - time.time())))
    stack.log("Past the deadline by " + str(round(time.time() - deadline)) + " seconds")
    # p1 first: its hold lookup interval grows with the time held (decision 122).
    next_order = pending_expiry(stack, p1, pending_payment, pending_approval)

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
                                  "pendingBeforeAdmission": p1, "afterPendingClosure": next_order,
                                  "pastDeadlineSeconds": round(time.time() - deadline)}
        stack.save_state()


if __name__ == "__main__":
    FakePortOneStack(REPO, "verify-expiry-journeys", offset_env="EXPIRY_JOURNEYS_TEST_PORT_OFFSET", prefix="expiry-journeys-").run(
        journey, "admitted PAY_PENDING past the deadline -> late FAILED EXPIRED, late PAID PAID; control order expired; "
                 "unadmitted PAY_PENDING at expiry held -> late PAID hold -> console cancel -> closure")
