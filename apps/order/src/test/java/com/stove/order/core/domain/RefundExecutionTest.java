package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefundExecutionTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
    private static final Instant RECEIVED_AT = PAID_AT.plusSeconds(3 * 3600);
    private static final Instant STOPPED_AT = PAID_AT.plusSeconds(6 * 3600);

    @Test void grantConfirmationKeepsTheIdentityOfTheOneIssuedSuspensionRequest() {
        Order paid = paid();
        Refund refund = Refund.request(paid, RECEIVED_AT);
        LicenseGrant grant = grant(paid);

        assertThat(refund.confirmLicenseGrant(grant)).isTrue();
        String issued = refund.getSuspensionRequestEventId();
        assertThat(issued).isNotBlank();
        assertThat(refund.confirmLicenseGrant(grant)).isFalse();
        assertThat(refund.getSuspensionRequestEventId()).isEqualTo(issued);
        assertThat(refund.getCurrentAttemptId()).isNull();
    }

    @Test void aMatchingSuspensionStartsTheFirstExecutionWithoutRecalculatingEligibility() {
        Order paid = paid();
        Refund refund = Refund.request(paid, RECEIVED_AT);
        LicenseGrant grant = grant(paid);
        refund.confirmLicenseGrant(grant);
        LicenseSuspensionConfirmation input = confirmation(refund, grant);

        RefundAttempt attempt = RefundAttempt.afterSuspension(refund, input, STOPPED_AT.plusSeconds(1));
        refund.recordExecution(attempt);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(refund.getCurrentAttemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(refund.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(refund.isAutomaticRefundEligible()).isTrue();
        assertThat(attempt.getRefundId()).isEqualTo(refund.getRefundId());
        assertThat(attempt.getAttemptNo()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(attempt.getSuspensionRequestEventId()).isEqualTo(input.requestEventId());
        assertThat(attempt.getSuspensionEventId()).isEqualTo(input.eventId());
        assertThat(attempt.getSuspensionOccurredAt()).isEqualTo(STOPPED_AT);
        assertThat(attempt.getLicenseId()).isEqualTo(grant.licenseId());
        assertThat(attempt.getExecutionRequestEventId()).isNotBlank();
        assertThat(attempt.getStartedAt()).isEqualTo(STOPPED_AT.plusSeconds(1));
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThatThrownBy(() -> refund.recordExecution(attempt)).isInstanceOf(RefundCompletionRejected.class);
    }

    @ParameterizedTest @ValueSource(strings = {"refund", "order", "request"})
    void unrelatedSuspensionsCannotAuthorizeAnExecution(String field) {
        Order paid = paid();
        Refund refund = Refund.request(paid, RECEIVED_AT);
        LicenseGrant grant = grant(paid);
        refund.confirmLicenseGrant(grant);
        LicenseSuspensionConfirmation original = confirmation(refund, grant);
        LicenseSuspensionConfirmation changed = new LicenseSuspensionConfirmation(original.eventId(), original.occurredAt(),
                field.equals("request") ? id() : original.requestEventId(), field.equals("refund") ? id() : original.refundId(),
                original.licenseId(), field.equals("order") ? id() : original.orderNo(), original.buyerSubject(),
                original.productId(), original.productCode(), original.paymentId());

        assertThatThrownBy(() -> RefundAttempt.afterSuspension(refund, changed, STOPPED_AT))
                .isInstanceOf(RefundCompletionRejected.class);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(refund.getCurrentAttemptId()).isNull();
    }

    @Test void noIssuedRequestAndManualReviewCannotBeTreatedAsSuspensionCompleted() {
        Order paid = paid();
        LicenseGrant grant = grant(paid);
        for (Instant receipt : new Instant[]{RECEIVED_AT, PAID_AT.plusSeconds(5 * 3600)}) {
            Refund refund = Refund.request(paid, receipt);
            LicenseSuspensionConfirmation input = new LicenseSuspensionConfirmation(id(), STOPPED_AT, id(),
                    refund.getRefundId(), grant.licenseId(), paid.getOrderNo(), paid.getBuyerSubject(),
                    paid.getProductId(), paid.getProductCode(), paid.getPaymentId());

            assertThatThrownBy(() -> RefundAttempt.afterSuspension(refund, input, STOPPED_AT))
                    .isInstanceOf(RefundCompletionRejected.class);
            assertThat(refund.getCurrentAttemptId()).isNull();
        }
    }

    @Test void redeliveryMustPreserveTheOriginalCompletionEvidence() {
        Order paid = paid();
        Refund refund = Refund.request(paid, RECEIVED_AT);
        LicenseGrant grant = grant(paid);
        refund.confirmLicenseGrant(grant);
        LicenseSuspensionConfirmation input = confirmation(refund, grant);
        RefundAttempt attempt = RefundAttempt.afterSuspension(refund, input, STOPPED_AT);
        attempt.verifySuspension(input);

        LicenseSuspensionConfirmation changed = new LicenseSuspensionConfirmation(input.eventId(), input.occurredAt().plusSeconds(1),
                input.requestEventId(), input.refundId(), input.licenseId(), input.orderNo(), input.buyerSubject(),
                input.productId(), input.productCode(), input.paymentId());

        assertThatThrownBy(() -> attempt.verifySuspension(changed)).isInstanceOf(RefundCompletionRejected.class);
        assertThat(attempt.getSuspensionOccurredAt()).isEqualTo(STOPPED_AT);
    }

    private static Order paid() {
        Order paid = order();
        paid.apply(new PaymentResult(id(), PAID_AT, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), OrderStatus.PAID, null, null));
        return paid;
    }

    private static LicenseGrant grant(Order paid) {
        return new LicenseGrant(id(), PAID_AT.plusSeconds(60), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
    }

    private static LicenseSuspensionConfirmation confirmation(Refund refund, LicenseGrant grant) {
        return new LicenseSuspensionConfirmation(id(), STOPPED_AT, refund.getSuspensionRequestEventId(), refund.getRefundId(),
                grant.licenseId(), grant.orderNo(), grant.buyerSubject(), grant.productId(), grant.productCode(), grant.paymentId());
    }
}
