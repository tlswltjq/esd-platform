package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundPaymentConfirmationTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-27T01:00:00.123456Z");
    private final Order paid = paid();
    private final Refund refund = Refund.request(paid, PAID_AT.plusSeconds(60));
    private final RefundAttempt attempt = start();

    @Test void refundCanCompleteWithoutClaimingThatTheExecutionSucceeded() {
        PaymentResult original = paid.paymentResult();
        assertThat(refund.confirmPayment(paid, attempt, input("cancel-1"))).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(attempt.getResolvedAt()).isNull();
        assertThat(paid.paymentResult()).isEqualTo(original);
    }

    @Test void sameCompletionIsIdempotentButChangedEvidenceCannotReplaceIt() {
        RefundPaymentConfirmation first = input("cancel-1");
        assertThat(refund.confirmPayment(paid, attempt, first)).isTrue();
        assertThat(refund.confirmPayment(paid, attempt, first)).isFalse();
        RefundPaymentConfirmation changed = new RefundPaymentConfirmation(first.eventId(), first.occurredAt(), first.refundId(),
                first.attemptId(), first.requestEventId(), first.orderNo(), first.paymentId(), first.buyerSubject(), first.amount(),
                first.currency(), first.source(), first.providerTransactionId(), List.of("other-cancel"));
        assertThatThrownBy(() -> refund.confirmPayment(paid, attempt, changed)).isInstanceOf(RefundCompletionRejected.class);
    }

    @Test void anUnrelatedExecutionCannotCompleteThisRefund() {
        assertThatThrownBy(() -> refund.confirmPayment(paid, RefundAttempt.start(refund.getRefundId(), 2, PAID_AT), input("cancel-1")))
                .isInstanceOf(RefundCompletionRejected.class);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
    }

    @Test void aDirectSuccessCompletesTheCustomerRefundAndTheAttemptTogether() {
        RefundPaymentConfirmation direct = response();
        refund.confirmPayment(paid, attempt, direct);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.SUCCEEDED);
        assertThat(attempt.getSuccessEventId()).isEqualTo(direct.eventId());
        assertThat(attempt.isReconciliationRequired()).isFalse();
    }

    @Test void aLateResponsePreservesTheFirstMoneyEventAndClearsOnlyTheUnknownAttempt() {
        RefundPaymentConfirmation query = input("cancel-1"); refund.confirmPayment(paid, attempt, query);
        RefundExecutionSuccess success = new RefundExecutionSuccess(response(), query.eventId());
        assertThat(refund.recordExecutionSuccess(paid, attempt, success)).isTrue();
        assertThat(refund.getPaymentConfirmationEventId()).isEqualTo(query.eventId());
        assertThat(refund.getPaymentConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.SUCCEEDED);
        assertThat(attempt.isReconciliationRequired()).isFalse();
    }

    @Test void aLateSuccessCanArriveBeforeTheEarlierMoneyEventWithoutChangingItsIdentity() {
        RefundPaymentConfirmation query = input("cancel-1");
        refund.recordExecutionSuccess(paid, attempt, new RefundExecutionSuccess(response(), query.eventId()));
        refund.confirmPayment(paid, attempt, query);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getPaymentConfirmationEventId()).isEqualTo(query.eventId());
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.SUCCEEDED);
        assertThat(attempt.isReconciliationRequired()).isFalse();
    }

    @Test void duplicateSuccessIsIdempotentButAnUnrelatedMoneyEventCannotReplaceTheFirstLink() {
        RefundPaymentConfirmation query = input("cancel-1"); refund.confirmPayment(paid, attempt, query);
        RefundExecutionSuccess first = new RefundExecutionSuccess(response(), query.eventId());
        assertThat(refund.recordExecutionSuccess(paid, attempt, first)).isTrue();
        assertThat(refund.recordExecutionSuccess(paid, attempt, first)).isFalse();
        assertThatThrownBy(() -> refund.recordExecutionSuccess(paid, attempt, new RefundExecutionSuccess(first.execution(), id())))
                .isInstanceOf(RefundCompletionRejected.class);
    }

    private RefundPaymentConfirmation response() {
        RefundPaymentConfirmation value = input("cancel-1");
        return new RefundPaymentConfirmation(value.eventId(), value.occurredAt(), value.refundId(), value.attemptId(),
                value.requestEventId(), value.orderNo(), value.paymentId(), value.buyerSubject(), value.amount(), value.currency(),
                "PG_RESPONSE", value.providerTransactionId(), value.cancellationIds());
    }

    private Order paid() {
        Order paid = order();
        paid.apply(new PaymentResult(id(), PAID_AT, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return paid;
    }
    private RefundAttempt start() {
        LicenseGrant grant = new LicenseGrant(id(), PAID_AT, id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        refund.confirmLicenseGrant(grant);
        LicenseSuspensionConfirmation suspension = new LicenseSuspensionConfirmation(id(), PAID_AT.plusSeconds(61),
                refund.getSuspensionRequestEventId(), refund.getRefundId(), grant.licenseId(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        RefundAttempt attempt = RefundAttempt.afterSuspension(refund, suspension, PAID_AT.plusSeconds(62));
        refund.recordExecution(attempt);
        return attempt;
    }
    private RefundPaymentConfirmation input(String cancellation) {
        return new RefundPaymentConfirmation(id(), PAID_AT.plusSeconds(100), refund.getRefundId(), attempt.getAttemptId(),
                attempt.getExecutionRequestEventId(), paid.getOrderNo(), paid.getPaymentId(), paid.getBuyerSubject(), paid.getAmount(),
                "KRW", "PG_QUERY_UNATTRIBUTED", "pg-original", List.of(cancellation));
    }
}
