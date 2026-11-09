#!/usr/bin/env python3
"""Local fake-PortOne purchase/refund/settlement/repurchase verification (decision 111)."""
from pathlib import Path
import json, sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/verification"))
from support.fake_portone_stack import FakePortOneStack  # noqa: E402


def verify_refund_totals(stack, orders, refunded_order):
    sql, fake = stack.sql, stack.fake
    # V10 stores positive reversal amounts; ReverseOriginalFullRefundPolicy copies
    # the original allocation. Net payout subtracts the positive refund amount.
    row = sql("SELECT r.amount,r.seller_payout_amount,s.seller_payout_amount,r.seller_subject,r.settlement_month "
              "FROM stove_settlement.settlement_refund r JOIN stove_settlement.settlement_sale s ON s.order_no=r.order_no "
              "WHERE r.order_no='" + refunded_order + "'").split("\t")
    assert int(row[0]) == stack.info["product"]["price"], "settlement refund amount differs from paid price"
    assert int(row[1]) == int(row[2]) and int(row[2]) - int(row[1]) == 0, "refund must reverse original positive seller payout"
    cancellation_ids = sorted(r["cancellationId"] for r in fake.requests
                              if r["path"].endswith("/cancel") and r["status"] == 200)
    stored_ids = sql("SELECT c.cancellation_id FROM stove_settlement.settlement_refund_cancellation c "
                     "JOIN stove_settlement.settlement_refund r ON r.id=c.refund_record_id "
                     "WHERE r.order_no='" + refunded_order + "' ORDER BY c.cancellation_id").splitlines()
    assert stored_ids and stored_ids == cancellation_ids, "Settlement cancellation IDs differ from cancel response"
    seller, month = row[3], row[4]
    scope = "seller_subject='" + seller + "' AND settlement_month='" + month + "'"
    own_orders = ",".join("'" + order + "'" for order in orders)
    expected = sql("SELECT COUNT(*),COALESCE(SUM(amount),0),COALESCE(SUM(seller_payout_amount),0) "
                   "FROM stove_settlement.settlement_refund WHERE " + scope + " AND order_no IN (" + own_orders + ")")
    actual = sql("SELECT refund_count,refund_amount,refunded_seller_payout_amount "
                 "FROM stove_settlement.seller_monthly_settlement WHERE " + scope)
    assert actual == expected, "monthly refund totals: expected " + expected + "; actual " + actual
    stack.record("refund allocation/cancellation/monthly totals (" + str(len(orders)) + " journey orders)", expected)


def journey(stack):
    buyer, sql, check, fake = None, stack.sql, stack.check, stack.fake
    subject, first = stack.prepare()
    buyer = stack.buyer

    # 1. First purchase (fake SDK approval) -> register.
    first = stack.pay("first", first, "첫 구매")
    first_license = stack.register(first)
    check("first sale", "SELECT COUNT(*) FROM stove_settlement.settlement_sale WHERE order_no='" + first + "'", 1, 180)

    # 2. Automatic refund (within the window) -> local fake cancel -> completed.
    stack.set_step("refund", "첫 구매를 환불하고 있습니다. 로컬 PortOne fake 취소 경로를 검증합니다.")
    status, value = buyer.call("POST", "/api/v1/orders/" + first + "/refund")
    assert status in {200, 202}, "refund HTTP " + str(status) + " " + json.dumps(value, ensure_ascii=False)
    stack.wait_for(lambda: buyer.data("/api/v1/orders/" + first + "/refund")["status"] == "COMPLETED", "refund COMPLETED", 120, 2)
    check("PurchaseRefunded", "SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='PurchaseRefunded' AND aggregate_id='" + first + "'", 1)
    check("license refunded", "SELECT status FROM stove_license.product_license WHERE order_no='" + first + "'", "REFUNDED")
    check("registration ended", "SELECT GROUP_CONCAT(status) FROM stove_license.license_registration WHERE order_no='" + first + "'", "ENDED")
    check("settlement refund", "SELECT COUNT(*) FROM stove_settlement.settlement_refund WHERE order_no='" + first + "'", 1, 120)
    verify_refund_totals(stack, [first], first)
    with stack.lock:
        stack.state["refund"] = {
            "order": buyer.data("/api/v1/orders/" + first + "/refund"),
            "settlementRefund": sql("SELECT CONCAT_WS('|',settlement_month,amount,seller_payout_amount) FROM stove_settlement.settlement_refund WHERE order_no='" + first + "'"),
            "monthly": sql("SELECT CONCAT_WS('|',settlement_month,paid_amount,refund_amount,seller_payout_amount,refunded_seller_payout_amount) FROM stove_settlement.seller_monthly_settlement"),
        }
        stack.save_state()

    # 3. Repurchase (fake SDK approval) -> register as a new license.
    again = stack.create_order("repurchase")
    again = stack.pay("repurchase", again, "재구매")
    again_license = stack.register(again)
    assert again_license != first_license
    check("one ACTIVE license", "SELECT COUNT(*) FROM stove_license.license_registration WHERE status='ACTIVE' AND order_no='" + again + "' AND buyer_subject='" + subject + "'", 1)
    check("repurchase sale", "SELECT COUNT(*) FROM stove_settlement.settlement_sale WHERE order_no='" + again + "'", 1, 180)
    verify_refund_totals(stack, [first, again], first)
    with stack.lock:
        stack.state["summary"] = {"first": first, "firstLicense": first_license, "repurchase": again, "repurchaseLicense": again_license,
                                  "sales": sql("SELECT GROUP_CONCAT(CONCAT_WS('|',order_no,settlement_month,amount,seller_payout_amount) ORDER BY id) FROM stove_settlement.settlement_sale"),
                                  "monthlyAfter": sql("SELECT CONCAT_WS('|',settlement_month,paid_amount,refund_amount,seller_payout_amount,refunded_seller_payout_amount) FROM stove_settlement.seller_monthly_settlement")}
        stack.save_state()
    for order in [first, again]:
        payment_id = buyer.data("/api/v1/payments/" + order)["paymentId"]
        all_confirms = stack.requests_for(payment_id, "/confirm")
        assert len(all_confirms) == 1, "exactly one confirm request per payment, including failures"
        confirms = [r for r in all_confirms if r["status"] == 200]
        assert len(confirms) == 1, "exactly one confirm per paid order"
    cancels = [r for r in fake.requests if r["method"] == "POST" and r["path"].endswith("/cancel")]
    assert len(cancels) == 1 and cancels[0]["status"] == 200
    refund_id = stack.state["refund"]["order"]["refundId"]
    assert cancels[0]["amount"] == stack.info["product"]["price"]
    assert cancels[0]["reason"] == "ESD refund " + refund_id
    first_payment = buyer.data("/api/v1/payments/" + first)["paymentId"]
    assert cancels[0]["path"] == "/payments/" + first_payment + "/cancel"
    for order in [first, again]:
        payment_id = buyer.data("/api/v1/payments/" + order)["paymentId"]
        assert any(r["caller"] == "settlement" and r["method"] == "GET" and r["status"] == 200
                   and r["path"].startswith("/payments/" + payment_id + "?") for r in fake.requests)
        stack.log("PASS: settlement GET payment for order " + order)
    assert not any(r["caller"] == "settlement" and r["method"] == "POST" for r in fake.requests)
    assert all(r["caller"] == "payment" for r in fake.requests if r["path"].endswith(("/confirm", "/cancel")))
    assert all(r["path"].startswith("/_fake/sdk/") for r in fake.requests if r["caller"] == "sdk")
    assert not any(r["path"].startswith("/_fake/control/") for r in fake.requests)
    stack.log("PASS: sdk only /_fake/sdk/*; exactly one total and successful confirm per payment")
    stack.log("PASS: settlement POST=0; all confirm/cancel callers=payment")
    if stack.settlement_compose:
        # Only the container holds the Settlement secret in this mode, so these reads came from it.
        cancel_index = fake.requests.index(cancels[0])
        assert any(r["caller"] == "settlement" and r["method"] == "GET" and r["status"] == 200
                   and r["path"].startswith("/payments/" + first_payment + "?") for r in fake.requests[cancel_index + 1:]), \
            "settlement container must read the cancelled payment after the cancel"
        assert stack.settlement_container_ready(), "settlement container must stay healthy"
        stack.record("settlement container approval/cancellation reads (GET after cancel)", "ok")


if __name__ == "__main__":
    compose = "--settlement-compose" in sys.argv[1:]
    FakePortOneStack(REPO, "verify-refund-settlement", offset_env="REFUND_SETTLEMENT_TEST_PORT_OFFSET", prefix="refund-settlement-",
                     settlement_compose=compose).run(
        journey, "local PortOne fake purchase -> register -> refund -> Settlement adjustment -> repurchase -> register"
        + (" (Settlement container via docker-compose.settlement-portone.yml)" if compose else ""))
