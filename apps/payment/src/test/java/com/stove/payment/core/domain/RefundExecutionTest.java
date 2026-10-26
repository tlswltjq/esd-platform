package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.approved;
import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PaymentFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RefundExecutionTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T01:00:00.123456Z");

    @Test void aFullRefundUsesTheDiscountedCustomerChargeRatherThanTheListPrice() {
        PaymentOrderRequest input = request();
        Payment paid = Payment.receive(new PaymentOrderRequest(input.eventId(), input.occurredAt(), input.orderNo(), input.buyerSubject(),
                input.productId(), input.productCode(), input.title(), input.contentVersion(), 10000, 1000, 9000, input.currency()), "SIMULATED");
        paid.confirm(approved(paid), id(), Payment.now());
        RefundExecutionRequest refund = execution(paid);
        assertThat(RefundExecution.receive(paid, refund, RECEIVED_AT).request().amount()).isEqualTo(9000);
        assertThat(paid.getListAmount()).isEqualTo(10000);
    }

    @Test void aReceiptPreservesTheOrderAttemptAndAllOriginalExecutionFacts() {
        Payment paid = paid();
        PgResult purchase = paid.supplierResult();
        RefundExecutionRequest input = execution(paid);

        RefundExecution receipt = RefundExecution.receive(paid, input, RECEIVED_AT);

        assertThat(receipt.getAttemptId()).isEqualTo(input.attemptId());
        assertThat(receipt.getRefundId()).isEqualTo(input.refundId());
        assertThat(receipt.getPaymentId()).isEqualTo(paid.getPaymentId());
        assertThat(receipt.request()).isEqualTo(input);
        assertThat(receipt.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(receipt.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.supplierResult()).isEqualTo(purchase);
        receipt.requireSameRequest(input);
    }

    @ParameterizedTest @ValueSource(strings = {"order", "payment", "buyer", "amount"})
    void aFullRefundMustMatchTheLocalConfirmedPayment(String field) {
        Payment paid = paid();
        RefundExecutionRequest original = execution(paid);
        RefundExecutionRequest changed = new RefundExecutionRequest(original.eventId(), original.occurredAt(), original.refundId(),
                original.attemptId(), field.equals("order") ? id() : original.orderNo(), field.equals("payment") ? id() : original.paymentId(),
                field.equals("buyer") ? "usr_other" : original.buyerSubject(), field.equals("amount") ? 9000 : original.amount(),
                original.currency(), original.licenseSuspensionEventId());

        assertThatThrownBy(() -> RefundExecution.receive(paid, changed, RECEIVED_AT)).isInstanceOf(BusinessException.class);
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @ParameterizedTest @EnumSource(value = PaymentStatus.class, names = {"PENDING", "FAILED"})
    void aPendingOrDeclinedPaymentCannotAuthorizeARefund(PaymentStatus status) {
        Payment payment = Payment.receive(request(), "SIMULATED");
        if (status == PaymentStatus.FAILED) payment.confirm(result(payment, PgResult.Decision.DECLINED), id(), Payment.now());

        assertThatThrownBy(() -> RefundExecution.receive(payment, execution(payment), RECEIVED_AT)).isInstanceOf(BusinessException.class);
        assertThat(payment.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest @ValueSource(strings = {"event", "time", "refund", "attempt", "suspension"})
    void aChangedDuplicateCannotReplaceTheReceivedExecution(String field) {
        Payment paid = paid();
        RefundExecutionRequest original = execution(paid);
        RefundExecution receipt = RefundExecution.receive(paid, original, RECEIVED_AT);
        RefundExecutionRequest changed = new RefundExecutionRequest(field.equals("event") ? id() : original.eventId(),
                field.equals("time") ? original.occurredAt().plusSeconds(1) : original.occurredAt(),
                field.equals("refund") ? id() : original.refundId(), field.equals("attempt") ? id() : original.attemptId(),
                original.orderNo(), original.paymentId(), original.buyerSubject(), original.amount(), original.currency(),
                field.equals("suspension") ? id() : original.licenseSuspensionEventId());

        assertThatThrownBy(() -> receipt.requireSameRequest(changed)).isInstanceOf(BusinessException.class);
        assertThat(receipt.request()).isEqualTo(original);
        assertThat(receipt.getReceivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test void aReceiptRequiresItsPaymentRequestAndDatabasePrecisionTime() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        assertThatThrownBy(() -> RefundExecution.receive(null, input, RECEIVED_AT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RefundExecution.receive(paid, null, RECEIVED_AT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RefundExecution.receive(paid, input, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RefundExecution.receive(paid, input, RECEIVED_AT.plusNanos(1))).isInstanceOf(BusinessException.class);
    }

    private static Payment paid() {
        Payment paid = Payment.receive(request(), "SIMULATED");
        paid.confirm(approved(paid), id(), Payment.now());
        return paid;
    }

    private static RefundExecutionRequest execution(Payment payment) {
        return new RefundExecutionRequest(id(), RECEIVED_AT.minusSeconds(1), id(), id(), payment.getOrderNo(),
                payment.getPaymentId(), payment.getBuyerSubject(), payment.getAmount(), payment.getCurrency(), id());
    }
}
