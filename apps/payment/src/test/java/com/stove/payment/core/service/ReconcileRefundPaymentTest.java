package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.RefundPaymentLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ReconcileRefundPaymentTest {
    private static final Instant NOW = Payment.now().plusSeconds(1);
    @Mock PaymentRepository payments;
    @Mock RefundExecutionRepository executions;
    @Mock RefundPaymentLookup lookup;
    @Mock OutboxRecorder outbox;
    @Mock PlatformTransactionManager transactions;

    @Test void queryPrecedesTheShortWriteTransactionAndPublishesOnlyAfterThePurchaseIsLocked() {
        RefundExecution received = received();
        Payment paid = payment;
        PgRefundConfirmation evidence = evidence(paid);
        when(lookup.find(evidence.purchase())).thenReturn(Optional.of(evidence));
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(payments.lockByPaymentId(paid.getPaymentId())).thenReturn(Optional.of(paid));
        when(executions.lockByAttemptId(received.getAttemptId())).thenReturn(Optional.of(received));
        service().execute(received.getAttemptId());
        InOrder order = inOrder(lookup, transactions, payments, executions, outbox);
        order.verify(lookup).find(evidence.purchase());
        order.verify(transactions).getTransaction(any(TransactionDefinition.class));
        order.verify(payments).lockByPaymentId(paid.getPaymentId());
        order.verify(executions).lockByAttemptId(received.getAttemptId());
        order.verify(outbox).record(any(), any(), any());
        order.verify(executions).flush();
    }

    @Test void absenceOfFullCancellationDoesNotStartAWriteTransactionOrCreateAFailure() {
        RefundExecution received = received();
        when(lookup.find(any(PgRefundLookupRequest.class))).thenReturn(Optional.empty());
        service().execute(received.getAttemptId());
        verifyNoInteractions(transactions, outbox);
    }

    @Test void anOuterTransactionIsRejectedBeforeAnySupplierOrRepositoryInteraction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service().execute(id())).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(payments, executions, lookup, transactions, outbox);
        } finally { TransactionSynchronizationManager.clear(); }
    }

    private Payment payment;
    private RefundExecution received() {
        payment = Payment.receive(request(), "PORTONE_TEST");
        payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW",
                "pg-original", NOW.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), NOW.minusSeconds(30));
        RefundExecution received = RefundExecution.receive(payment, execution(payment), NOW.minusSeconds(10));
        when(executions.findByAttemptId(received.getAttemptId())).thenReturn(Optional.of(received));
        when(payments.findByPaymentId(payment.getPaymentId())).thenReturn(Optional.of(payment));
        return received;
    }
    private PgRefundConfirmation evidence(Payment paid) {
        return new PgRefundConfirmation(new PgRefundLookupRequest(paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(),
                paid.getCurrency(), paid.getProviderTransactionId(), paid.getProviderOccurredAt()),
                List.of(new RefundCancellation("cancel-1", paid.getAmount(), NOW.minusSeconds(1))));
    }
    private ReconcileRefundPayment service() {
        return new ReconcileRefundPayment(payments, executions, lookup, outbox, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
