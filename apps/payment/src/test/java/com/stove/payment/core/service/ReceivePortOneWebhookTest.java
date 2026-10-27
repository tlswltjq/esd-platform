package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.approved;
import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.NOW;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import com.stove.payment.core.port.PortOneWebhookReceipts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReceivePortOneWebhookTest {
    @Mock PaymentRepository payments;
    @Mock PortOneWebhookReceipts receipts;

    @Test void durableReceptionOnlyWakesRecheckingAndDoesNotTrustTheClaimedPaidStatus() {
        Payment payment = pending();
        Instant due = NOW.plusSeconds(600);
        payment.defer(due, "WAITING_FOR_PG");
        PortOneWebhookNotice input = notice(payment);
        when(payments.lockByPaymentId(payment.getPaymentId())).thenReturn(Optional.of(payment));

        service().execute(input);

        verify(receipts).save(input, NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getResultEventId()).isNull();
        assertThat(payment.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test void identicalDeliveryDoesNotRewriteReceiptOrWakeTheSameHintRepeatedly() {
        Payment payment = pending();
        payment.defer(NOW.plusSeconds(600), "WAITING_FOR_PG");
        PortOneWebhookNotice input = notice(payment);
        when(payments.lockByPaymentId(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(receipts.findPayloadHash(input.messageId())).thenReturn(Optional.of(input.payloadHash()));
        service().execute(input);
        verify(receipts, never()).save(any(PortOneWebhookNotice.class), any(Instant.class));
        assertThat(payment.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test void changedPayloadUnderTheSameWebhookIdCannotBeAcceptedAsADuplicate() {
        Payment payment = pending();
        PortOneWebhookNotice input = notice(payment);
        when(payments.lockByPaymentId(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(receipts.findPayloadHash(input.messageId())).thenReturn(Optional.of("b".repeat(64)));
        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(BusinessException.class);
        verify(receipts, never()).save(any(PortOneWebhookNotice.class), any(Instant.class));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test void unknownPaymentAndAnotherProviderCannotCreateAPaymentOrReachTheReceiptStore() {
        Payment payment = pending();
        assertThatThrownBy(() -> service().execute(notice(payment))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(receipts);
        Payment simulated = Payment.receive(request(), "SIMULATED");
        when(payments.lockByPaymentId(simulated.getPaymentId())).thenReturn(Optional.of(simulated));
        assertThatThrownBy(() -> service().execute(notice(simulated))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(receipts);
    }

    @Test void notificationsAfterPaymentConfirmationDoNotRewriteThePurchaseDecision() {
        Payment payment = pending();
        PgResult fixture = approved(payment);
        PgResult original = new PgResult("PORTONE_TEST", fixture.paymentId(), fixture.orderNo(), fixture.amount(),
                fixture.currency(), fixture.transactionId(), fixture.occurredAt(), fixture.decision(), null, null);
        payment.confirm(original, id(), NOW);
        PortOneWebhookNotice input = notice(payment);
        when(payments.lockByPaymentId(payment.getPaymentId())).thenReturn(Optional.of(payment));
        service().execute(input);
        assertThat(payment.supplierResult()).isEqualTo(original);
        assertThat(payment.getNextAttemptAt()).isNull();
        verify(receipts).save(input, NOW);
    }

    private static Payment pending() { return Payment.receive(request(), "PORTONE_TEST"); }
    private static PortOneWebhookNotice notice(Payment payment) {
        return new PortOneWebhookNotice("msg_test", "a".repeat(64), "Transaction.Paid", STORE,
                payment.getPaymentId(), id(), NOW);
    }
    private ReceivePortOneWebhook service() {
        return new ReceivePortOneWebhook(payments, receipts, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
