package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderLicenseGrant;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
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

@ExtendWith(MockitoExtension.class)
class ApplyLicenseGrantTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
    private static final Instant RECEIVED_AT = PAID_AT.plusSeconds(3 * 3600);
    private static final Instant PROCESSED_AT = PAID_AT.plusSeconds(6 * 3600);
    @Mock OrderRepository orders;
    @Mock OrderLicenseGrantRepository grants;
    @Mock RefundRepository refunds;
    @Mock ProcessedEventGuard inbox;
    @Mock OutboxRecorder outbox;
    @Mock EntityManager entityManager;

    @Test
    void aGrantBeforeAnyRefundIsPreservedWithoutCreatingASuspensionRequest() {
        Order paid = paidOrder();
        LicenseGrant input = grant(paid);
        allowNewGrant(paid, input);

        service().execute(input);

        ArgumentCaptor<OrderLicenseGrant> stored = ArgumentCaptor.forClass(OrderLicenseGrant.class);
        verify(grants).save(stored.capture());
        assertThat(stored.getValue().confirmation()).isEqualTo(input);
        verifyNoInteractions(outbox);
        verify(entityManager).flush();
    }

    @Test
    void aLateConfirmationResumesTheOriginalEligibleRefundAndRequestsOnlySuspension() {
        Order paid = paidOrder();
        LicenseGrant input = grant(paid);
        Refund refund = Refund.request(paid, RECEIVED_AT);
        allowNewGrant(paid, input);
        when(refunds.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(refund));

        service().execute(input);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(refund.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(refund.isAutomaticRefundEligible()).isTrue();
        ArgumentCaptor<LicenseSuspensionRequestedEvent> event =
                ArgumentCaptor.forClass(LicenseSuspensionRequestedEvent.class);
        verify(outbox).record(eq("Order"), eq(paid.getOrderNo()), event.capture());
        LicenseSuspensionRequestedEvent stop = event.getValue();
        assertThat(stop.eventId()).isEqualTo(refund.getSuspensionRequestEventId());
        assertThat(stop.refundId()).isEqualTo(refund.getRefundId());
        assertThat(stop.occurredAt()).isEqualTo(PROCESSED_AT);
        assertThat(stop.orderNo()).isEqualTo(input.orderNo());
        assertThat(stop.buyerSubject()).isEqualTo(input.buyerSubject());
        assertThat(stop.productId()).isEqualTo(input.productId());
        assertThat(stop.productCode()).isEqualTo(input.productCode());
        assertThat(stop.paymentId()).isEqualTo(input.paymentId());
        assertThat(stop.topic()).isEqualTo("stove.order.v1");
        assertThat(stop.partitionKey()).isEqualTo(input.orderNo());
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void anIdenticalGrantDoesNotIssueAnotherSuspensionRequest() {
        Order paid = paidOrder();
        LicenseGrant input = grant(paid);
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));
        when(grants.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(OrderLicenseGrant.record(input)));

        service().execute(input);

        verifyNoInteractions(inbox, refunds, outbox, entityManager);
    }

    @Test
    void aGrantDoesNotApproveARefundWaitingForAdministratorReview() {
        Order paid = paidOrder();
        LicenseGrant input = grant(paid);
        Refund refund = Refund.request(paid, PAID_AT.plusSeconds(5 * 3600));
        allowNewGrant(paid, input);
        when(refunds.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(refund));

        service().execute(input);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyer", "productId", "productCode", "paymentId"})
    void mismatchedPurchaseEvidenceIsRejectedBeforeInboxOrPersistence(String field) {
        Order paid = paidOrder();
        LicenseGrant original = grant(paid);
        LicenseGrant input = new LicenseGrant(original.eventId(), original.occurredAt(), original.licenseId(),
                original.orderNo(), field.equals("buyer") ? "usr_other" : original.buyerSubject(),
                field.equals("productId") ? 99L : original.productId(),
                field.equals("productCode") ? "Game-99" : original.productCode(),
                field.equals("paymentId") ? id() : original.paymentId());
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));

        assertThatThrownBy(() -> service().execute(input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verifyNoInteractions(inbox, grants, refunds, outbox, entityManager);
    }

    @Test
    void changedRedeliveryCannotReplaceTheStoredLicenseIdentity() {
        Order paid = paidOrder();
        LicenseGrant original = grant(paid);
        LicenseGrant changed = new LicenseGrant(original.eventId(), original.occurredAt(), id(), original.orderNo(),
                original.buyerSubject(), original.productId(), original.productCode(), original.paymentId());
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));
        when(grants.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(OrderLicenseGrant.record(original)));

        assertThatThrownBy(() -> service().execute(changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(inbox, refunds, outbox, entityManager);
    }

    private ApplyLicenseGrant service() {
        return new ApplyLicenseGrant(orders, grants, refunds, inbox, outbox, entityManager,
                Clock.fixed(PROCESSED_AT, ZoneOffset.UTC));
    }

    private void allowNewGrant(Order paid, LicenseGrant input) {
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));
        when(inbox.firstDelivery(input.eventId(), ApplyLicenseGrant.CONSUMER_GROUP, "LicenseGranted")).thenReturn(true);
    }

    private static Order paidOrder() {
        Order paid = order();
        paid.apply(new PaymentResult(id(), PAID_AT, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), OrderStatus.PAID, null, null));
        return paid;
    }

    private static LicenseGrant grant(Order paid) {
        return new LicenseGrant(id(), PAID_AT.plusSeconds(60), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
    }
}
