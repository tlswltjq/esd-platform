#!/usr/bin/env python3
"""G1 closure journeys on the real stack with the widened PortOne fake (decision 119).

(b) Approval without our admission: the PortOne console approves the attempt without our confirm
    -> Payment holds it as PAID_WITHOUT_ADMISSION (ADMIN list, gauge) -> closure refused while PAID
    -> console full cancel -> ADMIN closure -> Payment FAILED(REVIEW_CLOSED) -> Order FAILED (decision 115)
    -> same order cannot be paid again; the buyer is sent to a new order.
(a) That new order: attempt 1 FAILED in the payment window (no approval), attempt 2 admitted then FAILED
    at confirm (PG decline) -> Order back to PENDING with both failures in the history -> same order,
    same paymentId, attempt 3 PAID -> OrderPaid -> License granted and registered.

30-minute expiry combinations are out of scope until the user decides them (decision 119).
"""
from pathlib import Path
import json, sys, uuid

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/verification"))
from support.fake_portone_stack import FakePortOneStack  # noqa: E402

GAUGE = "stove_payment_approval_review_required"


def error_code(value):
    return (value.get("error") or {}).get("code")


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


def held(admin, approval_id):
    page = admin.data("/api/v1/admin/approval-reviews?reason=PAID_WITHOUT_ADMISSION&size=50")
    return next((row for row in page["content"] if row["approvalId"] == approval_id), None)


def held_order(admin, order_no):
    page = admin.data("/api/v1/admin/approval-reviews?reason=PAID_WITHOUT_ADMISSION&size=50")
    return [row["approvalId"] for row in page["content"] if row["orderNo"] == order_no]


def failures(buyer, order_no):
    return buyer.data("/api/v1/payments/" + order_no + "/attempt-failures?size=20")


def journey(stack):
    sql, fake, record, wait_for = stack.sql, stack.fake, stack.record, stack.wait_for
    subject, first = stack.prepare()
    buyer = stack.buyer
    price = stack.info["product"]["price"]

    # ---- (b) PAID without admission -> hold -> console cancel -> closure ---------------------------------
    stack.wait_payment_pending(first)
    stack.set_step("unadmitted-paid", "콘솔 자동 승인처럼 confirm 없이 PAID가 된 결제를 보류로 확인합니다.", order_no=first)
    checkout, sdk = stack.open_checkout(first)
    payment_id = checkout["paymentId"]
    stack.console(payment_id, "console-approve")
    reception = stack.submit_approval(first, sdk)
    approval_id = reception["requestId"]  # public receipt id = approvalId used by the ADMIN API
    approval_path = "/api/v1/payments/" + first + "/approvals/" + approval_id
    wait_for(lambda: buyer.data(approval_path)["status"] == "REVIEW_REQUIRED", "approval REVIEW_REQUIRED", 60)
    record("b1 approval held", "REVIEW_REQUIRED")

    admin = stack.admin()
    wait_for(lambda: held_order(admin, first) != [], "ADMIN list has the held order", 60)
    assert held_order(admin, first) == [approval_id], (held_order(admin, first), approval_id)
    wait_for(lambda: held(admin, approval_id) is not None and held(admin, approval_id)["latestPgState"] == "PAID",
             "ADMIN list shows PAID_WITHOUT_ADMISSION", 60)
    row = held(admin, approval_id)
    assert (row["reason"], row["admitted"], row["status"], row["orderNo"]) == ("PAID_WITHOUT_ADMISSION", False, "REVIEW_REQUIRED", first), row
    record("b2 ADMIN list reason|admitted|latestPgState", "PAID_WITHOUT_ADMISSION|False|PAID")
    wait_for(lambda: stack.metric("payment", GAUGE, {"reason": "PAID_WITHOUT_ADMISSION"}) == 1.0, "hold gauge = 1", 60)
    record("b3 gauge " + GAUGE + "{reason=PAID_WITHOUT_ADMISSION}", 1)

    order, payment = buyer.data("/api/v1/orders/" + first), buyer.data("/api/v1/payments/" + first)
    assert (order["status"], payment["status"]) == ("PENDING", "PENDING"), (order["status"], payment["status"])
    assert stack.requests_for(payment_id, "/confirm") == [], "a held unadmitted PAID must never be confirmed"
    record("b4 held: order|payment|confirm POSTs", "PENDING|PENDING|0")

    status, value = close_review(stack, admin, approval_id, str(uuid.uuid4()), "PG 콘솔 취소 전 종결 시도")
    assert (status, error_code(value)) == (409, "CLOSURE_PG_PAID"), (status, value)
    assert buyer.data(approval_path)["status"] == "REVIEW_REQUIRED"
    record("b5 closure while PG PAID", "409 CLOSURE_PG_PAID")

    stack.set_step("console-cancel", "운영자가 PortOne 콘솔에서 전액 취소한 뒤 종결합니다.", order_no=first)
    stack.console(payment_id, "console-cancel")
    wait_for(lambda: held(admin, approval_id)["latestPgState"] == "CANCELLED", "held approval observes CANCELLED", 90, 2)
    row = held(admin, approval_id)
    assert (row["reason"], row["status"]) == ("PAID_WITHOUT_ADMISSION", "REVIEW_REQUIRED"), row
    record("b6 after console cancel: reason kept|latestPgState", "PAID_WITHOUT_ADMISSION|CANCELLED")

    closure_request = str(uuid.uuid4())
    status, closed = close_review(stack, admin, approval_id, closure_request, "PG 콘솔 전액 취소 확인 후 종결")
    assert status == 200, (status, closed)
    closed = closed["data"]
    assert (closed["approvalStatus"], closed["paymentStatus"], closed["closure"]["evidenceState"]) == ("FAILED", "FAILED", "CANCELLED"), closed
    record("b7 closure approval|payment|evidence", "FAILED|FAILED|CANCELLED")
    lookups = len([r for r in fake.requests if r["method"] == "GET" and r["path"].startswith("/payments/" + payment_id)])
    status, replay = close_review(stack, admin, approval_id, closure_request, "PG 콘솔 전액 취소 확인 후 종결")
    assert status == 200 and replay["data"] == closed, (status, replay)
    assert len([r for r in fake.requests if r["method"] == "GET" and r["path"].startswith("/payments/" + payment_id)]) == lookups
    record("b8 closure replay same requestId: same receipt, PG GETs added", 0)

    wait_for(lambda: buyer.data("/api/v1/orders/" + first)["status"] == "FAILED", "order FAILED " + first, 120)
    order, payment = buyer.data("/api/v1/orders/" + first), buyer.data("/api/v1/payments/" + first)
    assert (payment["status"], payment["failureReasonCode"], order["failureReasonCode"]) == ("FAILED", "REVIEW_CLOSED", "REVIEW_CLOSED"), (payment, order)
    record("b9 end state order|payment|reasonCode", "FAILED|FAILED|REVIEW_CLOSED")
    stack.check("b10 OrderPaymentFailed outbox", "SELECT COUNT(*) FROM stove_payment.outbox_event WHERE event_type='OrderPaymentFailed' AND aggregate_id='" + first + "'", 1)
    stack.check("b11 no OrderPaid/License for closed order",
                "SELECT (SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND aggregate_id='" + first + "')"
                "+(SELECT COUNT(*) FROM stove_license.product_license WHERE order_no='" + first + "')"
                "+(SELECT COUNT(*) FROM stove_settlement.settlement_sale WHERE order_no='" + first + "')", 0)

    # Next action after closure (decision 115): this order is finished; the buyer starts a new order.
    status, value = buyer.call("GET", "/api/v1/payments/" + first + "/checkout")
    assert status == 409, (status, value)
    status, value = buyer.call("POST", "/api/v1/payments/" + first + "/approvals", dict(requestId=str(uuid.uuid4()), **sdk))
    assert status == 409, (status, value)
    wait_for(lambda: stack.metric("payment", GAUGE, {"reason": "PAID_WITHOUT_ADMISSION"}) == 0.0, "hold gauge back to 0", 60)
    assert held(admin, approval_id) is None, "closed approval must leave the hold list"
    detail = admin.data("/api/v1/admin/approval-reviews/" + approval_id)
    assert detail["status"] == "FAILED" and detail["closure"]["requestId"] == closure_request, detail
    record("b12 next action: checkout|new approval|gauge|hold list|detail closure", "409|409|0|absent|kept")
    assert not any(r["caller"] == "payment" and r["path"].endswith("/cancel") for r in fake.requests), "system must not cancel at PG"

    # ---- (a) new order: FAILED attempts -> same order retry -> PAID ---------------------------------------
    second = stack.create_order("after-closure")
    stack.wait_payment_pending(second)
    record("b13 new order after closure", "PENDING")
    stack.set_step("window-failed", "결제창에서 실패한 시도(승인 접수 없음)를 이력으로 확인합니다.", order_no=second)
    checkout1, sdk1 = stack.open_checkout(second)
    payment2 = checkout1["paymentId"]
    stack.console(payment2, "fail-attempt")
    wait_for(lambda: failures(buyer, second)["totalElements"] == 1, "failure history 1", 90, 2)
    order, payment = buyer.data("/api/v1/orders/" + second), buyer.data("/api/v1/payments/" + second)
    assert (order["status"], payment["status"]) == ("PENDING", "PENDING"), (order, payment)
    record("a1 window FAILED: history|order|payment", "1|PENDING|PENDING")

    stack.set_step("confirm-failed", "승인 접수 뒤 confirm에서 PG가 거절한 시도를 확인합니다.", order_no=second)
    checkout2, sdk2 = stack.open_checkout(second)
    assert (checkout2["paymentId"], checkout2["totalAmount"]) == (payment2, checkout1["totalAmount"]), (checkout1, checkout2)
    assert sdk2["txId"] != sdk1["txId"]
    stack.console(payment2, "arm-confirm", {"outcome": "FAILED"})
    reception2 = stack.submit_approval(second, sdk2)
    wait_for(lambda: buyer.data("/api/v1/payments/" + second + "/approvals/" + reception2["requestId"])["status"] == "FAILED",
             "admitted approval FAILED after Order notification", 120, 2)
    stack.check("a2 Order admission of attempt 2 closed back to PENDING",
                "SELECT CONCAT_WS('|',COUNT(*),MAX(closed_order_status)) FROM stove_order.order_payment_admission WHERE order_no='" + second
                + "' AND failed_supplier_attempt_id='" + sdk2["txId"] + "'", "1|PENDING")
    wait_for(lambda: failures(buyer, second)["totalElements"] == 2, "failure history 2", 90, 2)
    order, payment = buyer.data("/api/v1/orders/" + second), buyer.data("/api/v1/payments/" + second)
    assert (order["status"], payment["status"]) == ("PENDING", "PENDING"), (order, payment)
    confirms = stack.requests_for(payment2, "/confirm")
    assert [r["status"] for r in confirms] == [502], confirms
    record("a3 confirm PG decline: confirm statuses|history|order|payment", "502|2|PENDING|PENDING")
    first_failures = sorted(row["attemptId"] for row in failures(buyer, second)["content"])

    stack.set_step("retry-paid", "같은 주문·같은 paymentId로 다시 결제합니다.", order_no=second)
    checkout3, sdk3 = stack.open_checkout(second)
    assert checkout3["paymentId"] == payment2 and sdk3["txId"] not in {sdk1["txId"], sdk2["txId"]}
    stack.submit_approval(second, sdk3)
    wait_for(lambda: buyer.data("/api/v1/orders/" + second)["status"] == "PAID", "order PAID " + second, 180)
    stack.check("a4 OrderPaid outbox", "SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND aggregate_id='" + second + "'", 1)
    stack.check("a5 OrderPaymentCompleted outbox", "SELECT COUNT(*) FROM stove_payment.outbox_event WHERE event_type='OrderPaymentCompleted' AND aggregate_id='" + second + "'", 1)
    license_id = stack.register(second)
    record("a6 License granted and registered", "ACTIVE")
    stack.check("a7 one ACTIVE registration for buyer", "SELECT COUNT(*) FROM stove_license.license_registration WHERE status='ACTIVE' AND buyer_subject='" + subject + "'", 1)
    history = failures(buyer, second)
    assert history["totalElements"] == 2 and sorted(row["attemptId"] for row in history["content"]) == first_failures, history
    payment = buyer.data("/api/v1/payments/" + second)
    assert payment["status"] == "PAID" and payment["amount"] == price, payment
    confirms = stack.requests_for(payment2, "/confirm")
    assert [r["status"] for r in confirms] == [502, 200], confirms
    statuses = [item["status"] for item in fake.view_history(payment2)]
    assert statuses == ["FAILED", "FAILED", "PAID"], statuses
    record("a8 after PAID: failure ids kept|confirm statuses|PortOne history", "2|502,200|FAILED,FAILED,PAID")

    assert all(r["caller"] == "console" for r in fake.requests if r["path"].startswith("/_fake/control/"))
    assert all(r["caller"] in {"sdk", "console"} for r in fake.requests if r["path"].startswith("/_fake/"))
    assert all(r["caller"] == "payment" for r in fake.requests if r["path"].endswith(("/confirm", "/cancel")))
    assert not any(r["path"].endswith("/cancel") for r in fake.requests), "no API cancel in these journeys"
    record("a9 fake callers: control=console, confirm=payment, API cancel", 0)
    with stack.lock:
        stack.state["summary"] = {"closedOrder": first, "closedApproval": approval_id, "retriedOrder": second,
                                  "retriedPayment": payment2, "license": license_id, "failedAttempts": first_failures}
        stack.save_state()


if __name__ == "__main__":
    FakePortOneStack(REPO, "verify-approval-journeys", offset_env="APPROVAL_JOURNEYS_TEST_PORT_OFFSET", prefix="approval-journeys-").run(
        journey, "unadmitted PAID hold -> console cancel -> closure -> new order -> FAILED attempts -> same-order retry PAID -> License")
