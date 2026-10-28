package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundPreparationTest {
    private static final Instant BASE=Instant.parse("2026-10-29T01:00:00.123456Z");
    @Test void theExactDelayBoundaryUsesPaymentPreparationRatherThanTheEarlierOrderRequest() {
        Fixture f=fixture(); RefundPreparationConfirmation input=input(f);
        assertThat(f.attempt().recordPreparation(f.order(),input)).isTrue();
        assertThat(f.attempt().confirmationDelayedSince(BASE.plusSeconds(10800))).isNull();
        assertThat(f.attempt().confirmationDelayedSince(input.confirmationDelayAt().minusNanos(1000))).isNull();
        assertThat(f.attempt().confirmationDelayedSince(input.confirmationDelayAt())).isEqualTo(input.confirmationDelayAt());
    }
    @Test void exactRedeliveryDoesNotResetTheFirstPreparationOrTheDelayBoundary() {
        Fixture f=fixture(); RefundPreparationConfirmation input=input(f);
        assertThat(f.attempt().recordPreparation(f.order(),input)).isTrue();
        assertThat(f.attempt().recordPreparation(f.order(),input)).isFalse();
        assertThat(f.attempt().confirmationDelayedSince(input.confirmationDelayAt().plusSeconds(86400))).isEqualTo(input.confirmationDelayAt());
    }
    @Test void aChangedPreparationCannotRewriteTheSameExecution() {
        Fixture f=fixture(); RefundPreparationConfirmation input=input(f); f.attempt().recordPreparation(f.order(),input);
        RefundPreparationConfirmation changed=new RefundPreparationConfirmation(input.eventId(),input.occurredAt().plusSeconds(1),input.refundId(),input.attemptId(),
                input.requestEventId(),input.orderNo(),input.paymentId(),input.buyerSubject(),input.amount(),input.currency(),input.licenseSuspensionEventId(),null,
                input.firstPreparedAt().plusSeconds(1),input.confirmationDelayAt().plusSeconds(1));
        assertThatThrownBy(()->f.attempt().recordPreparation(f.order(),changed)).isInstanceOf(RefundCompletionRejected.class);
    }
    @Test void anUnrelatedPurchaseCannotProvidePreparationEvidence() {
        Fixture f=fixture(); RefundPreparationConfirmation input=input(f);
        assertThatThrownBy(()->f.attempt().recordPreparation(order(),input)).isInstanceOf(RefundCompletionRejected.class);
    }
    private RefundPreparationConfirmation input(Fixture f) {
        return new RefundPreparationConfirmation(id(),BASE.plusSeconds(1800),f.refund().getRefundId(),f.attempt().getAttemptId(),f.attempt().getExecutionRequestEventId(),
                f.order().getOrderNo(),f.order().getPaymentId(),f.order().getBuyerSubject(),f.order().getAmount(),f.order().getCurrency(),f.attempt().getSuspensionEventId(),null,
                BASE.plusSeconds(1800),BASE.plusSeconds(12600));
    }
    private Fixture fixture() {
        Order paid=order(); paid.apply(new PaymentResult(id(),BASE.minusSeconds(7200),paid.getOrderNo(),paid.getRequestEventId(),id(),paid.getBuyerSubject(),paid.getAmount(),paid.getCurrency(),OrderStatus.PAID,null,null));
        Refund refund=Refund.request(paid,BASE.minusSeconds(1)); LicenseGrant grant=new LicenseGrant(id(),BASE.minusSeconds(10),id(),paid.getOrderNo(),paid.getBuyerSubject(),paid.getProductId(),paid.getProductCode(),paid.getPaymentId());
        refund.confirmLicenseGrant(grant); LicenseSuspensionConfirmation suspended=new LicenseSuspensionConfirmation(id(),BASE,refund.getSuspensionRequestEventId(),refund.getRefundId(),grant.licenseId(),paid.getOrderNo(),paid.getBuyerSubject(),paid.getProductId(),paid.getProductCode(),paid.getPaymentId());
        RefundAttempt attempt=RefundAttempt.afterSuspension(refund,suspended,BASE); refund.recordExecution(attempt); return new Fixture(paid,refund,attempt);
    }
    private record Fixture(Order order,Refund refund,RefundAttempt attempt) { }
}
