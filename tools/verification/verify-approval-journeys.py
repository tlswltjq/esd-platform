#!/usr/bin/env python3
"""G1 closure journeys on the real stack with the widened PortOne fake (decision 119).

(b) Approval without our admission: the PortOne console approves the attempt without our confirm
    -> Payment holds it as PAID_WITHOUT_ADMISSION (ADMIN list, gauge) -> closure refused while PAID
    -> console full cancel -> ADMIN closure -> Payment FAILED(REVIEW_CLOSED) -> Order FAILED (decision 115)
    -> same order cannot be paid again; the buyer is sent to a new order.
(a) That new order: attempt 1 FAILED in the payment window (no approval), attempt 2 admitted then FAILED
    at confirm (PG decline) -> Order back to PENDING with both failures in the history -> same order,
    same paymentId, attempt 3 PAID -> OrderPaid -> License granted and registered.

(c) Risky exit/re-entry combinations inside the 30-minute order deadline (decision 120), each on its own new order:
    c1 the window authenticated an attempt but our approval request never arrived -> same order, new attempt PAID
    c2 while an admitted approval is still processing (PG PAY_PENDING) the window is refused 409 APPROVAL_IN_PROGRESS
    c3 PAY_PENDING stays processing (no re-POST, lookups only), a new order buys the same product meanwhile
       (allowed, Order R2.5, decisions 89/90/93) and both orders end PAID with both purchases kept in the storage
    c4 an admitted PAY_PENDING approval settles FAILED -> Order back to PENDING -> same order paid again
    c5 confirm 400 / 403 -> PG_REQUEST_REJECTED hold (no re-POST, window refused) -> ADMIN closure on READY evidence

Combinations past the 30-minute deadline live in verify-expiry-journeys.py; 3-hour combinations follow decision 124.
"""
from pathlib import Path
import json, sys, time, uuid

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


def held_reason(admin, reason, approval_id):
    page = admin.data("/api/v1/admin/approval-reviews?reason=" + reason + "&size=50")
    return next((row for row in page["content"] if row["approvalId"] == approval_id), None)


def storage_item(stack, order_no):
    return next((item for item in stack.storage() if item["orderNo"] == order_no), None)


def outbox(stack, schema, event_type, order_no):
    return "SELECT COUNT(*) FROM stove_" + schema + ".outbox_event WHERE event_type='" + event_type + "' AND aggregate_id='" + order_no + "'"


def kept_in_storage(stack, name, order_no):
    """A PAID re-purchase stays in the storage; the library already holds the product (License R10.4, decisions 90/93)."""
    stack.wait_for(lambda: storage_item(stack, order_no) is not None, "storage has " + order_no, 120, 2)
    item = storage_item(stack, order_no)
    status, value = stack.buyer.call("POST", "/api/v1/storage/" + item["licenseId"] + "/registration")
    assert (status, error_code(value)) == (409, "PRODUCT_ALREADY_OWNED"), (status, value)
    assert storage_item(stack, order_no)["status"] == "STORED"
    stack.record(name + " storage kept|registration", "STORED|409 PRODUCT_ALREADY_OWNED")


def reentry(stack):
    """Decision 120 (c): risky combinations inside the 30-minute deadline. The buyer already holds the product from (a)."""
    buyer, wait_for, record, check = stack.buyer, stack.wait_for, stack.record, stack.check

    # ---- c1 approval request never reached us -> same order, new attempt PAID ----------------------------
    c1 = stack.create_order("c1-unsent-approval")
    stack.wait_payment_pending(c1)
    stack.set_step("c1-unsent", "결제창 인증 뒤 승인 요청이 서버에 닿지 않은 시도를 같은 주문으로 다시 결제합니다.", order_no=c1)
    checkout1, sdk1 = stack.open_checkout(c1)
    payment1 = checkout1["paymentId"]
    time.sleep(6)  # more than one Payment poll; nothing may move without our approval request
    assert stack.statuses(c1) == ("PENDING", "PENDING"), stack.statuses(c1)
    assert stack.confirm_statuses(payment1) == [] and failures(buyer, c1)["totalElements"] == 0
    record("c1 unsent approval: order|payment|confirm POSTs|failure history", "PENDING|PENDING|0|0")
    checkout2, sdk2 = stack.open_checkout(c1)
    assert (checkout2["paymentId"], checkout2["totalAmount"]) == (payment1, checkout1["totalAmount"]) and sdk2["txId"] != sdk1["txId"]
    stack.submit_approval(c1, sdk2)
    wait_for(lambda: stack.statuses(c1) == ("PAID", "PAID"), "c1 PAID", 180)
    check("c1 OrderPaid outbox", outbox(stack, "order", "OrderPaid", c1), 1)
    wait_for(lambda: failures(buyer, c1)["checkedAt"] is not None and failures(buyer, c1)["collectionPending"] is False,
             "c1 failure history collected after PAID", 120, 2)
    history = failures(buyer, c1)
    assert history["totalElements"] == 0, history  # an abandoned window is not a failed attempt (R5.6)
    assert stack.confirm_statuses(payment1) == [200], stack.confirm_statuses(payment1)
    assert [i["status"] for i in stack.fake.view_history(payment1)] == ["READY", "PAID"]
    status, value = buyer.call("GET", "/api/v1/payments/" + c1 + "/checkout")
    assert status == 409, (status, value)
    record("c1 retry: order|payment|confirm statuses|PortOne history|failure history|checkout", "PAID|PAID|200|READY,PAID|0|409")
    kept_in_storage(stack, "c1", c1)

    # ---- c2/c3 PAY_PENDING while processing: window refused, kept pending, double purchase ---------------
    c3 = stack.create_order("c3-pay-pending")
    stack.set_step("c2-in-progress", "승인 처리 중(PG PAY_PENDING) 결제창 재요청이 거절되는지 확인합니다.", order_no=c3)
    payment3, sdk3, approval3 = stack.admit_pay_pending(c3)
    status, value = buyer.call("GET", "/api/v1/payments/" + c3 + "/checkout")
    assert (status, error_code(value)) == (409, "APPROVAL_IN_PROGRESS"), (status, value)
    status, value = buyer.call("POST", "/api/v1/payments/" + c3 + "/approvals", dict(requestId=str(uuid.uuid4()), **sdk3))
    assert status == 409, (status, value)
    record("c2 processing: checkout|second approval", "409 APPROVAL_IN_PROGRESS|409")

    stack.set_step("c3-pay-pending", "PAY_PENDING이 처리 중으로 남고 조회만 이어지는지 확인합니다.", order_no=c3)
    before = stack.lookups(payment3)
    time.sleep(20)  # four Payment retry periods (PAYMENT_RETRY_MS=5000)
    assert stack.statuses(c3, approval3) == ("PAYMENT_PROCESSING", "PENDING", "ADMITTED"), stack.statuses(c3, approval3)
    assert stack.lookups(payment3) >= before + 2, (before, stack.lookups(payment3))
    assert stack.confirm_statuses(payment3) == [200], stack.confirm_statuses(payment3)
    status, value = buyer.call("GET", "/api/v1/payments/" + c3 + "/checkout")
    assert (status, error_code(value)) == (409, "APPROVAL_IN_PROGRESS"), (status, value)
    record("c3 PAY_PENDING kept: order|payment|approval|re-POST|lookups grow|checkout",
           "PAYMENT_PROCESSING|PENDING|ADMITTED|0|yes|409 APPROVAL_IN_PROGRESS")

    stack.set_step("c3-double-purchase", "처리 중에 새 주문으로 같은 상품을 삽니다(설계상 허용).", order_no=c3)
    double = stack.create_order("c3-double")
    stack.pay("c3-double-paid", double, "처리 중 새 주문을 결제합니다.")
    assert stack.statuses(c3, approval3) == ("PAYMENT_PROCESSING", "PENDING", "ADMITTED")
    record("c3 new order while processing: new order|original", "PAID|PAYMENT_PROCESSING")
    stack.console(payment3, "settle", {"status": "PAID"})
    wait_for(lambda: stack.statuses(c3, approval3) == ("PAID", "PAID", "SUCCEEDED"), "c3 late settle PAID", 180, 2)
    for order_no in (c3, double):
        check("c3 OrderPaid outbox " + order_no, outbox(stack, "order", "OrderPaid", order_no), 1)
        check("c3 purchase evidence " + order_no, "SELECT COUNT(*) FROM stove_license.product_license WHERE order_no='" + order_no + "'", 1)
    assert stack.confirm_statuses(payment3) == [200]
    record("c3 both PAID: original order|payment|approval", "PAID|PAID|SUCCEEDED")
    kept_in_storage(stack, "c3 original", c3)
    kept_in_storage(stack, "c3 double", double)

    # ---- c4 admitted PAY_PENDING settles FAILED -> back to PENDING -> same order PAID ---------------------
    c4 = stack.create_order("c4-pending-failed")
    stack.set_step("c4-pending-failed", "승인 처리 중 PG가 FAILED로 끝난 뒤 같은 주문으로 다시 결제합니다.", order_no=c4)
    payment4, sdk4, approval4 = stack.admit_pay_pending(c4)
    stack.console(payment4, "settle", {"status": "FAILED"})
    wait_for(lambda: stack.statuses(c4, approval4) == ("PENDING", "PENDING", "FAILED"), "c4 back to PENDING", 120, 2)
    check("c4 Order admission closed back to PENDING",
          "SELECT CONCAT_WS('|',COUNT(*),MAX(closed_order_status)) FROM stove_order.order_payment_admission WHERE order_no='" + c4
          + "' AND failed_supplier_attempt_id='" + sdk4["txId"] + "'", "1|PENDING")
    wait_for(lambda: failures(buyer, c4)["totalElements"] == 1, "c4 failure history 1", 90, 2)
    record("c4 settle FAILED: order|payment|approval|failure history", "PENDING|PENDING|FAILED|1")
    checkout4, sdk4b = stack.open_checkout(c4)
    assert checkout4["paymentId"] == payment4 and sdk4b["txId"] != sdk4["txId"]
    stack.submit_approval(c4, sdk4b)
    wait_for(lambda: stack.statuses(c4) == ("PAID", "PAID"), "c4 PAID", 180)
    check("c4 OrderPaid outbox", outbox(stack, "order", "OrderPaid", c4), 1)
    assert stack.confirm_statuses(payment4) == [200, 200], stack.confirm_statuses(payment4)
    assert [i["status"] for i in stack.fake.view_history(payment4)] == ["FAILED", "PAID"]
    assert failures(buyer, c4)["totalElements"] == 1
    record("c4 retry: order|payment|confirm statuses|PortOne history|failure history", "PAID|PAID|200,200|FAILED,PAID|1")
    kept_in_storage(stack, "c4", c4)

    # ---- c5 confirm 400 / 403 -> PG_REQUEST_REJECTED hold -> closure on READY ----------------------------
    admin = stack.admin()
    rejected = []
    for index, (outcome, http) in enumerate((("REJECT_400", 400), ("REJECT_403", 403)), start=1):
        order_no = stack.create_order("c5-" + outcome)
        stack.wait_payment_pending(order_no)
        stack.set_step("c5-" + outcome, "confirm이 " + str(http) + "으로 거절되면 PG_REQUEST_REJECTED 보류가 되는지 확인합니다.", order_no=order_no)
        checkout, sdk = stack.open_checkout(order_no)
        payment_id = checkout["paymentId"]
        stack.console(payment_id, "arm-confirm", {"outcome": outcome})
        approval_id = stack.submit_approval(order_no, sdk)["requestId"]
        wait_for(lambda: stack.statuses(order_no, approval_id) == ("PAYMENT_PROCESSING", "PENDING", "REVIEW_REQUIRED"),
                 "c5 hold " + outcome, 120, 2)
        wait_for(lambda: held_reason(admin, "PG_REQUEST_REJECTED", approval_id) is not None, "c5 ADMIN list " + outcome, 60)
        row = held_reason(admin, "PG_REQUEST_REJECTED", approval_id)
        assert (row["reason"], row["admitted"], row["status"], row["orderNo"]) == ("PG_REQUEST_REJECTED", True, "REVIEW_REQUIRED", order_no), row
        time.sleep(12)  # the hold must stop further confirm POSTs
        assert stack.confirm_statuses(payment_id) == [http], stack.confirm_statuses(payment_id)
        assert stack.fake.view_history(payment_id)[-1]["status"] == "READY"
        status, value = buyer.call("GET", "/api/v1/payments/" + order_no + "/checkout")
        assert (status, error_code(value)) == (409, "APPROVAL_IN_PROGRESS"), (status, value)
        wait_for(lambda: stack.metric("payment", GAUGE, {"reason": "PG_REQUEST_REJECTED"}) == float(index), "rejected gauge " + str(index), 60)
        record("c5 confirm " + str(http) + ": order|payment|approval|reason|admitted|confirm POSTs|checkout|gauge",
               "PAYMENT_PROCESSING|PENDING|REVIEW_REQUIRED|PG_REQUEST_REJECTED|True|" + str(http) + "|409 APPROVAL_IN_PROGRESS|" + str(index))
        rejected.append((order_no, approval_id, payment_id))

    stack.set_step("c5-closure", "PG READY 근거로 거절 보류를 종결하고 새 주문 안내로 끝나는지 확인합니다.")
    for order_no, approval_id, payment_id in rejected:
        wait_for(lambda: held_reason(admin, "PG_REQUEST_REJECTED", approval_id)["latestPgState"] == "READY", "c5 latest READY", 60, 2)
        status, closed = close_review(stack, admin, approval_id, str(uuid.uuid4()), "PG 승인 요청 거절 확인, 청구 없음")
        assert status == 200, (status, closed)
        closed = closed["data"]
        assert (closed["approvalStatus"], closed["paymentStatus"], closed["closure"]["evidenceState"]) == ("FAILED", "FAILED", "READY"), closed
        wait_for(lambda: buyer.data("/api/v1/orders/" + order_no)["status"] == "FAILED", "c5 order FAILED " + order_no, 120)
        order, payment = buyer.data("/api/v1/orders/" + order_no), buyer.data("/api/v1/payments/" + order_no)
        assert (payment["status"], payment["failureReasonCode"], order["failureReasonCode"]) == ("FAILED", "REVIEW_CLOSED", "REVIEW_CLOSED"), (payment, order)
        status, value = buyer.call("GET", "/api/v1/payments/" + order_no + "/checkout")
        assert status == 409, (status, value)
        check("c5 no OrderPaid/License " + order_no, "SELECT (" + outbox(stack, "order", "OrderPaid", order_no) + ")"
              "+(SELECT COUNT(*) FROM stove_license.product_license WHERE order_no='" + order_no + "')", 0)
        assert stack.confirm_statuses(payment_id) in ([400], [403])
    wait_for(lambda: stack.metric("payment", GAUGE, {"reason": "PG_REQUEST_REJECTED"}) == 0.0, "rejected gauge 0", 60)
    record("c5 closure: approval|payment|evidence|order|reasonCode|checkout|gauge", "FAILED|FAILED|READY|FAILED|REVIEW_CLOSED|409|0")

    assert all(r["caller"] == "payment" for r in stack.fake.requests if r["path"].endswith(("/confirm", "/cancel")))
    assert not any(r["path"].endswith("/cancel") for r in stack.fake.requests), "no API cancel in these journeys"
    record("c6 fake callers after (c): confirm=payment, API cancel", 0)
    with stack.lock:
        stack.state["reentry"] = {"unsent": c1, "payPending": c3, "doublePurchase": double, "pendingFailed": c4,
                                  "rejected": [order_no for order_no, _, _ in rejected]}
        stack.save_state()


def journeys(stack):
    journey(stack)
    reentry(stack)


if __name__ == "__main__":
    FakePortOneStack(REPO, "verify-approval-journeys", offset_env="APPROVAL_JOURNEYS_TEST_PORT_OFFSET", prefix="approval-journeys-").run(
        journeys, "unadmitted PAID hold -> closure -> FAILED attempts -> same-order retry PAID -> License; "
                  "30-minute re-entry combinations (decision 120)")
