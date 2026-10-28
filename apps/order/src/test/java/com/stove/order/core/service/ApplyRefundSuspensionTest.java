package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderLicenseGrant;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundAttemptStatus;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;

@ExtendWith(MockitoExtension.class)
class ApplyRefundSuspensionTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
    private static final Instant RECEIVED_AT = PAID_AT.plusSeconds(3 * 3600);
    private static final Instant STOPPED_AT = PAID_AT.plusSeconds(6 * 3600);
    private static final Instant EXECUTION_AT = STOPPED_AT.plusSeconds(1);
    @Mock OrderRepository orders;
    @Mock RefundRepository refunds;
    @Mock OrderLicenseGrantRepository grants;
    @Mock RefundAttemptRepository attempts;
    @Mock ProcessedEventGuard inbox;
    @Mock OutboxRecorder outbox;
    @Mock EntityManager entityManager;

    @Test void aMatchingCompletionRegistersOneExecutionUsingTheOriginalPaidAmount() {
        Fixture fixture = fixture();
        known(fixture);
        when(inbox.firstDelivery(fixture.input().eventId(), ApplyRefundSuspension.CONSUMER_GROUP, "LicenseSuspended"))
                .thenReturn(true);

        service().execute(fixture.input());

        ArgumentCaptor<RefundAttempt> saved = ArgumentCaptor.forClass(RefundAttempt.class);
        verify(attempts).save(saved.capture());
        RefundAttempt attempt = saved.getValue();
        assertThat(attempt.getAttemptNo()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(fixture.refund().getCurrentAttemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(fixture.refund().getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(fixture.refund().getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(fixture.refund().isAutomaticRefundEligible()).isTrue();
        ArgumentCaptor<RefundExecutionRequestedEvent> event = ArgumentCaptor.forClass(RefundExecutionRequestedEvent.class);
        verify(outbox).record(eq("Order"), eq(fixture.order().getOrderNo()), event.capture());
        RefundExecutionRequestedEvent requested = event.getValue();
        assertThat(requested.eventId()).isEqualTo(attempt.getExecutionRequestEventId());
        assertThat(requested.occurredAt()).isEqualTo(EXECUTION_AT);
        assertThat(requested.refundId()).isEqualTo(fixture.refund().getRefundId());
        assertThat(requested.attemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(requested.orderNo()).isEqualTo(fixture.order().getOrderNo());
        assertThat(requested.paymentId()).isEqualTo(fixture.order().getPaymentId());
        assertThat(requested.buyerSubject()).isEqualTo(fixture.order().getBuyerSubject());
        assertThat(requested.amount()).isEqualTo(fixture.order().getAmount());
        assertThat(requested.currency()).isEqualTo(fixture.order().getCurrency());
        assertThat(requested.licenseSuspensionEventId()).isEqualTo(fixture.input().eventId());
        assertThat(requested.topic()).isEqualTo("stove.order.v1");
        assertThat(requested.partitionKey()).isEqualTo(fixture.order().getOrderNo());
        verifyNoMoreInteractions(outbox);
        verify(entityManager).flush();
    }

    @Test void anIdenticalCompletionDoesNotCreateAnotherAttemptOrRequest() {
        Fixture fixture = fixture();
        known(fixture);
        RefundAttempt previous = RefundAttempt.afterSuspension(fixture.refund(), fixture.input(), EXECUTION_AT);
        fixture.refund().recordExecution(previous);
        when(attempts.findBySuspensionEventId(fixture.input().eventId())).thenReturn(Optional.of(previous));

        service().execute(fixture.input());

        verifyNoInteractions(outbox, entityManager);
        assertThat(fixture.refund().getCurrentAttemptId()).isEqualTo(previous.getAttemptId());
        assertThat(previous.getStartedAt()).isEqualTo(EXECUTION_AT);
    }

    @ParameterizedTest @ValueSource(strings = {"refund", "request", "license", "buyer", "productId", "productCode", "payment"})
    void mismatchedEvidenceCannotAdvanceTheRefund(String field) {
        Fixture fixture = fixture();
        known(fixture);
        LicenseSuspensionConfirmation input = fixture.input();
        LicenseSuspensionConfirmation changed = new LicenseSuspensionConfirmation(input.eventId(), input.occurredAt(),
                field.equals("request") ? id() : input.requestEventId(), field.equals("refund") ? id() : input.refundId(),
                field.equals("license") ? id() : input.licenseId(), input.orderNo(),
                field.equals("buyer") ? "usr_other" : input.buyerSubject(), field.equals("productId") ? 99L : input.productId(),
                field.equals("productCode") ? "Other-1" : input.productCode(), field.equals("payment") ? id() : input.paymentId());

        assertThatThrownBy(() -> service().execute(changed)).isInstanceOf(RefundCompletionRejected.class);

        assertThat(fixture.refund().getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(fixture.refund().getCurrentAttemptId()).isNull();
        verifyNoInteractions(inbox, outbox, entityManager);
    }

    @Test void aMissingOrderIsRejectedInsteadOfBeingReservedForLater() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> service().execute(fixture.input())).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(refunds, grants, attempts, inbox, outbox, entityManager);
    }

    @Test void aMissingRefundIsRejectedWithoutCreatingAReplacement() {
        Fixture fixture = fixture();
        when(orders.lockByOrderNo(fixture.input().orderNo())).thenReturn(Optional.of(fixture.order()));
        assertThatThrownBy(() -> service().execute(fixture.input())).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(grants, attempts, inbox, outbox, entityManager);
    }

    @Test void anUnconfirmedGrantCannotBeInferredFromACompletionMessage() {
        Fixture fixture = fixture();
        when(orders.lockByOrderNo(fixture.input().orderNo())).thenReturn(Optional.of(fixture.order()));
        when(refunds.findByOrderNo(fixture.input().orderNo())).thenReturn(Optional.of(fixture.refund()));
        assertThatThrownBy(() -> service().execute(fixture.input())).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(attempts, inbox, outbox, entityManager);
    }

    @Test void aTransientDatabaseFailureRemainsRetryable() {
        Fixture fixture = fixture();
        when(orders.lockByOrderNo(fixture.input().orderNo())).thenThrow(new TransientDataAccessResourceException("temporarily unavailable"));
        assertThatThrownBy(() -> service().execute(fixture.input())).isInstanceOf(TransientDataAccessResourceException.class);
        verifyNoInteractions(refunds, grants, attempts, inbox, outbox, entityManager);
    }

    @Test void changedRedeliveryCannotReplaceTheStoredCompletion() {
        Fixture fixture = fixture();
        known(fixture);
        RefundAttempt previous = RefundAttempt.afterSuspension(fixture.refund(), fixture.input(), EXECUTION_AT);
        fixture.refund().recordExecution(previous);
        when(attempts.findBySuspensionEventId(fixture.input().eventId())).thenReturn(Optional.of(previous));
        LicenseSuspensionConfirmation input = fixture.input();
        LicenseSuspensionConfirmation changed = new LicenseSuspensionConfirmation(input.eventId(), input.occurredAt().plusSeconds(1),
                input.requestEventId(), input.refundId(), input.licenseId(), input.orderNo(), input.buyerSubject(),
                input.productId(), input.productCode(), input.paymentId());
        assertThatThrownBy(() -> service().execute(changed)).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(inbox, outbox, entityManager);
        assertThat(previous.getSuspensionOccurredAt()).isEqualTo(STOPPED_AT);
    }

    @Test void anAttemptWithoutTheCurrentPointerCannotBeReplacedByAnotherFirstAttempt() {
        Fixture fixture = fixture();
        known(fixture);
        when(attempts.existsByRefundId(fixture.refund().getRefundId())).thenReturn(true);
        assertThatThrownBy(() -> service().execute(fixture.input())).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(inbox, outbox, entityManager);
    }

    @Test void aCompletionIdAlreadyUsedElsewhereIsRejectedBeforeInbox() {
        Fixture fixture = fixture();
        known(fixture);
        when(attempts.findBySuspensionEventId(fixture.input().eventId()))
                .thenReturn(Optional.of(RefundAttempt.start(id(), 1, EXECUTION_AT)));
        assertThatThrownBy(() -> service().execute(fixture.input())).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(inbox, outbox, entityManager);
    }

    private ApplyRefundSuspension service() {
        return new ApplyRefundSuspension(orders, refunds, grants, attempts, inbox, outbox, entityManager,
                Clock.fixed(EXECUTION_AT, ZoneOffset.UTC), mock(ApplyApprovalCorrection.class));
    }

    private void known(Fixture fixture) {
        when(orders.lockByOrderNo(fixture.order().getOrderNo())).thenReturn(Optional.of(fixture.order()));
        when(refunds.findByOrderNo(fixture.order().getOrderNo())).thenReturn(Optional.of(fixture.refund()));
        when(grants.findByOrderNo(fixture.order().getOrderNo())).thenReturn(Optional.of(OrderLicenseGrant.record(fixture.grant())));
    }

    private static Fixture fixture() {
        Order paid = order();
        paid.apply(new PaymentResult(id(), PAID_AT, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), OrderStatus.PAID, null, null));
        LicenseGrant grant = new LicenseGrant(id(), PAID_AT.plusSeconds(60), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        Refund refund = Refund.request(paid, RECEIVED_AT);
        refund.confirmLicenseGrant(grant);
        LicenseSuspensionConfirmation input = new LicenseSuspensionConfirmation(id(), STOPPED_AT,
                refund.getSuspensionRequestEventId(), refund.getRefundId(), grant.licenseId(), paid.getOrderNo(),
                paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        return new Fixture(paid, refund, grant, input);
    }

    private record Fixture(Order order, Refund refund, LicenseGrant grant, LicenseSuspensionConfirmation input) {}
}
