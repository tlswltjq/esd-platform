#!/usr/bin/env python3
"""PortOne webhook hints on the real stack with the PortOne fake (decision 137).

The fake signs Standard Webhooks messages and sends them straight to Payment (no Gateway), like the
2026-10-27 edge. Payment runs with PAYMENT_RETRY_MS=180000 here: an undecided PG lookup is retried three
minutes later, so a webhook (Payment.requestRecheck -> next poll) is visible against a control order in
the same PG state whose messages the fake holds back.

w1 approval hint: orders A and B are admitted while the PG is still deciding (confirm answers PAY_PENDING),
   then the fake settles both PAID. A's Transaction.Paid is delivered -> one receipt, a PG lookup right away,
   Payment and Order PAID. B's messages are held -> over the same window B stays PENDING with no new PG
   lookup. Releasing B's held messages then pays B too.
w2 redelivery: A's Transaction.Paid again with the same id and body -> 200, still one receipt for that id.
w3 forged: the same message signed with another key -> 401, no receipt added.
w4 cancellation hint: orders C and D are paid and refunded; the PG accepts both cancels as REQUESTED (still
   pending), so each refund lookup reads "undecided" and backs off (RefundLookupSchedule 10/20/60 s). After
   three such lookups the PG finishes both cancels. C's Transaction.Cancelled is delivered -> the refund
   lookup is woken and the refund COMPLETES while D, whose message is held, waits for its own schedule.
   A failed lookup is retried at the next scan instead (RefundLookupSchedule.abandon), so lost responses or
   PG errors would not show the hint.

The real PortOne delivery (public HTTPS, provider format) stays an external wait (Gateway tasks.md).
"""
from pathlib import Path
import sys, time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/verification"))
from support.fake_portone_stack import FakePortOneStack  # noqa: E402

RETRY_MS = 180000
HINT_WINDOW = 30      # seconds a delivered hint may take; far below the 180 s retry and the 60 s refund stage
UNDECIDED_LOOKUPS = 3


def receipts(stack, payment_id, message_id=None):
    where = "payment_id='" + payment_id + "'" + ("" if message_id is None else " AND message_id='" + message_id + "'")
    return int(stack.sql("SELECT COUNT(*) FROM stove_payment.payment_pg_webhook WHERE " + where))


def deliveries(stack, payment_id, message_type, kind):
    return [d for d in stack.fake.webhooks_for(payment_id) if d["type"] == message_type and d["kind"] == kind]


def lookups_after_cancel(stack, payment_id):
    """Payment's successful GET /payments/{id} calls after its cancel POST (refund lookups that read the PG state)."""
    requests = stack.fake.requests
    cancel = next((i for i, r in enumerate(requests) if r["method"] == "POST"
                   and r["path"] == "/payments/" + payment_id + "/cancel"), None)
    if cancel is None:
        return 0
    return len([r for r in requests[cancel + 1:] if r["method"] == "GET" and r["caller"] == "payment" and r["status"] == 200
                and r["path"].split("?")[0] == "/payments/" + payment_id])


def refund_status(stack, order_no):
    return stack.buyer.data("/api/v1/orders/" + order_no + "/refund")["status"]


def admitted_pay_pending(stack, order_no, mode):
    """Admit an approval whose confirm answers PAY_PENDING; the payment's webhook mode is set before the confirm."""
    stack.wait_payment_pending(order_no)
    checkout, sdk = stack.open_checkout(order_no)
    payment_id = checkout["paymentId"]
    stack.fake.webhook_mode(payment_id, mode)
    stack.console(payment_id, "arm-confirm", {"outcome": "PAY_PENDING"})
    approval_id = stack.submit_approval(order_no, sdk)["requestId"]
    stack.wait_for(lambda: stack.confirm_statuses(payment_id) == [200]
                   and stack.statuses(order_no, approval_id) == ("PAYMENT_PROCESSING", "PENDING", "ADMITTED"),
                   "admitted PAY_PENDING " + order_no, 120, 2)
    assert stack.fake.view_history(payment_id)[-1]["status"] == "PAY_PENDING"
    return payment_id


def approval_hint(stack, first):
    fake, record, wait_for = stack.fake, stack.record, stack.wait_for
    held_order = stack.create_order("w1-held")
    stack.set_step("w1-admit", "두 주문을 PG 판단 대기(PAY_PENDING)로 접수합니다. B의 웹훅은 붙잡아 둡니다.", order_no=first)
    hinted = admitted_pay_pending(stack, first, "deliver")
    held = admitted_pay_pending(stack, held_order, "hold")
    quiet = stack.lookups(held)
    time.sleep(10)
    assert stack.lookups(held) == quiet, "held order must be in the long PG lookup wait before the settle"
    record("w1a held order: PG lookups during 10 s before settle", 0)

    hinted_lookups, held_lookups, held_receipts = stack.lookups(hinted), stack.lookups(held), receipts(stack, held)
    stack.set_step("w1-settle", "PG가 두 결제를 모두 PAID로 정합니다. A에만 Transaction.Paid가 도착합니다.", order_no=first)
    settled = time.monotonic()
    stack.console(hinted, "settle", {"status": "PAID"})
    stack.console(held, "settle", {"status": "PAID"})
    wait_for(lambda: stack.statuses(first) == ("PAID", "PAID"), "hinted order PAID", HINT_WINDOW, 1)
    seconds = round(time.monotonic() - settled, 1)
    fake.flush_webhooks()
    paid = deliveries(stack, hinted, "Transaction.Paid", "delivered")
    assert [d["status"] for d in paid] == [200], paid
    assert receipts(stack, hinted, paid[0]["messageId"]) == 1
    assert stack.lookups(hinted) > hinted_lookups, "the hint must lead to a PG lookup, not decide PAID by itself"
    record("w1b hinted: Transaction.Paid status|receipts|PG lookup after hint|order|payment", "200|1|yes|PAID|PAID")
    record("w1b hinted: seconds from PG PAID to Order PAID", seconds)

    remaining = HINT_WINDOW - (time.monotonic() - settled)
    if remaining > 0:
        time.sleep(remaining)
    assert stack.statuses(held_order) == ("PAYMENT_PROCESSING", "PENDING"), stack.statuses(held_order)
    assert stack.lookups(held) == held_lookups and receipts(stack, held) == held_receipts
    assert deliveries(stack, held, "Transaction.Paid", "delivered") == []
    record("w1c held control after " + str(HINT_WINDOW) + " s: order|payment|new PG lookups|new receipts",
           "PAYMENT_PROCESSING|PENDING|0|0")

    released = fake.release_webhooks(held)
    assert [fake.messages[m]["type"] for m in released] == ["Transaction.PayPending", "Transaction.Paid"], released
    released_at = time.monotonic()
    wait_for(lambda: stack.statuses(held_order) == ("PAID", "PAID"), "released control order PAID", HINT_WINDOW, 1)
    record("w1d released held hints -> control order PAID, seconds", round(time.monotonic() - released_at, 1))
    stack.check("w1e OrderPaid for both orders",
                "SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND aggregate_id IN ('"
                + first + "','" + held_order + "')", 2)
    return hinted, paid[0]["messageId"]


def redelivery_and_forgery(stack, payment_id, message_id):
    fake, record = stack.fake, stack.record
    stack.set_step("w2-redelivery", "같은 웹훅을 다시 보내고(같은 ID·본문), 다른 키로 서명한 위조본을 보냅니다.")
    before = receipts(stack, payment_id)
    fake.redeliver(message_id)
    fake.deliver_forged(message_id)
    fake.flush_webhooks()
    again = [d for d in fake.webhooks_for(payment_id) if d["messageId"] == message_id and d["kind"] == "redelivered"]
    forged = [d for d in fake.webhooks_for(payment_id) if d["messageId"] == message_id and d["kind"] == "forged"]
    assert [d["status"] for d in again] == [200], again
    assert [d["status"] for d in forged] == [401], forged
    assert receipts(stack, payment_id, message_id) == 1 and receipts(stack, payment_id) == before
    record("w2 redelivery same id/body: status|receipts for id", "200|1")
    record("w3 forged signature: status|receipts added", "401|0")


def cancellation_hint(stack):
    fake, record, wait_for = stack.fake, stack.record, stack.wait_for
    woken, waiting = stack.create_order("w4-woken"), stack.create_order("w4-waiting")
    orders = {}
    for name, order_no in (("w4-woken", woken), ("w4-waiting", waiting)):
        stack.pay(name, order_no, name + " 구매")
        stack.check(name + " license grant confirmed",
                    "SELECT COUNT(*) FROM stove_order.order_license_grant WHERE order_no='" + order_no + "'", 1, 120)
        payment_id = stack.buyer.data("/api/v1/payments/" + order_no)["paymentId"]
        stack.console(payment_id, "arm-cancel", {"outcome": "REQUESTED"})
        orders[name] = (order_no, payment_id)
    stack.set_step("w4-refund", "PG가 두 환불의 취소를 접수만 해 둡니다(REQUESTED). 취소를 마친 뒤 C의 알림만 보냅니다.")
    for name, (order_no, _) in orders.items():
        status, _, raw = stack.buyer.request("POST", "/api/v1/orders/" + order_no + "/refund")
        assert status in {200, 202}, name + " refund HTTP " + str(status) + " " + raw.decode()
    (woken_order, woken_payment), (waiting_order, waiting_payment) = orders["w4-woken"], orders["w4-waiting"]
    wait_for(lambda: lookups_after_cancel(stack, woken_payment) >= UNDECIDED_LOOKUPS
             and lookups_after_cancel(stack, waiting_payment) >= UNDECIDED_LOOKUPS, "three undecided refund lookups each", 180, 1)
    for _, payment_id in orders.values():
        assert [r["status"] for r in stack.requests_for(payment_id, "/cancel")] == [200], payment_id
    assert refund_status(stack, woken_order) != "COMPLETED" and refund_status(stack, waiting_order) != "COMPLETED"
    record("w4a cancel REQUESTED and " + str(UNDECIDED_LOOKUPS) + " undecided lookups: both refunds unconfirmed", "yes")

    fake.webhook_mode(waiting_payment, "hold")
    stack.console(woken_payment, "settle-cancel")
    stack.console(waiting_payment, "settle-cancel")
    woken_at = time.monotonic()
    wait_for(lambda: refund_status(stack, woken_order) == "COMPLETED", "woken refund COMPLETED", HINT_WINDOW, 1)
    woken_seconds = round(time.monotonic() - woken_at, 1)
    fake.flush_webhooks()
    cancelled = deliveries(stack, woken_payment, "Transaction.Cancelled", "delivered")
    assert [d["status"] for d in cancelled] == [200], cancelled
    assert refund_status(stack, waiting_order) != "COMPLETED", "the control refund must still wait for its schedule"
    assert deliveries(stack, waiting_payment, "Transaction.Cancelled", "delivered") == []
    record("w4b woken: Transaction.Cancelled status|refund|control refund", "200|COMPLETED|not yet")
    record("w4b woken: seconds from PG cancel to refund COMPLETED", woken_seconds)

    wait_for(lambda: refund_status(stack, waiting_order) == "COMPLETED", "control refund COMPLETED on its schedule", 180, 2)
    record("w4c control refund COMPLETED on its own schedule, seconds after the woken one",
           round(time.monotonic() - woken_at, 1))
    for name, (order_no, _) in orders.items():
        stack.check(name + " license refunded",
                    "SELECT status FROM stove_license.product_license WHERE order_no='" + order_no + "'", "REFUNDED")


def journey(stack):
    _, first = stack.prepare()
    payment_id, message_id = approval_hint(stack, first)
    redelivery_and_forgery(stack, payment_id, message_id)
    cancellation_hint(stack)


if __name__ == "__main__":
    FakePortOneStack(REPO, "verify-webhook-journeys", offset_env="WEBHOOK_JOURNEYS_TEST_PORT_OFFSET",
                     prefix="webhook-journeys-", webhooks=True, payment_retry_ms=RETRY_MS).run(
        journey, "PortOne webhook hints: approval and cancellation lookups woken by signed webhooks; "
                 "redelivery kept one receipt, forged signature refused (decision 137)")
