package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgRefundCancellationSuccess;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundCancellationDispatch;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.RefundCancellationGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RequestRefundCancellationTest {
    private static final Instant NOW = Payment.now();
    @Mock PaymentRepository payments;
    @Mock RefundExecutionRepository executions;
    @Mock RefundCancellationGateway gateway;
    @Mock ReconcileRefundPayment reconcile;
    @Mock PlatformTransactionManager transactions;
    @Mock RecordRefundCancellationSuccess successes;

    @Test void theFirstWireSnapshotIsCommittedBeforeAnyExternalSubmission() {
        RefundExecution received = fixture();
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.UNCONFIRMED);
        service().execute(received.getAttemptId());
        InOrder order = inOrder(executions, transactions, gateway, reconcile);
        order.verify(executions).flush();
        order.verify(transactions).commit(any());
        order.verify(gateway).send(any(), any(), anyString());
        order.verify(reconcile).execute(received.getAttemptId());
        assertThat(received.getCancellationRequestBody()).isEqualTo("frozen-wire-body");
    }

    @Test void aFailedCommitCannotEscapeAsAPgSubmission() {
        RefundExecution received = fixture();
        doThrow(new TransientDataAccessResourceException("commit failure")).when(transactions).commit(any());
        assertThatThrownBy(() -> service().execute(received.getAttemptId())).isInstanceOf(TransientDataAccessResourceException.class);
        verifyNoInteractions(reconcile);
        verify(gateway).prepareBody(received.request());
        verify(gateway, never()).send(any(), any(), anyString());
    }

    @Test void changedBalanceIsStoredAsAStopAndStillAllowsResultReconciliation() {
        RefundExecution received = fixture();
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.BALANCE_CHANGED);
        service().execute(received.getAttemptId());
        assertThat(received.getCancellationBalanceChangedAt()).isEqualTo(NOW);
        verify(reconcile).execute(received.getAttemptId());
    }

    @Test void aValidatedSuccessUsesItsOwnAtomicRecorderWithoutRequiringAGet() {
        RefundExecution received = fixture(); Payment paid = payments.findByPaymentId(received.getPaymentId()).orElseThrow();
        PgRefundCancellationSuccess proof = new PgRefundCancellationSuccess(new PgRefundLookupRequest(paid.getPaymentId(), paid.getOrderNo(),
                paid.getAmount(), "KRW", paid.getProviderTransactionId(), paid.getProviderOccurredAt()), received.getAttemptId(),
                new RefundCancellation("cancel-1", paid.getAmount(), null), NOW, 0, 0, "ESD refund " + received.getRefundId());
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.succeeded(proof));
        service().execute(received.getAttemptId());
        verify(successes).execute(received.getAttemptId(), proof); verifyNoInteractions(reconcile);
    }

    @Test void anOuterTransactionIsRejectedBeforeAnySupplierOrRepositoryAccess() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service().execute(id())).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(payments, executions, gateway, reconcile, transactions);
        } finally { TransactionSynchronizationManager.clear(); }
    }

    private RefundExecution fixture() {
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW",
                "pg-original", NOW.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), NOW.minusSeconds(30));
        RefundExecution received = RefundExecution.receive(paid, execution(paid), NOW);
        when(executions.findByAttemptId(received.getAttemptId())).thenReturn(Optional.of(received));
        when(executions.lockByAttemptId(received.getAttemptId())).thenReturn(Optional.of(received));
        when(payments.findByPaymentId(paid.getPaymentId())).thenReturn(Optional.of(paid));
        when(payments.lockByPaymentId(paid.getPaymentId())).thenReturn(Optional.of(paid));
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(gateway.prepareBody(received.request())).thenReturn("frozen-wire-body");
        return received;
    }
    private RequestRefundCancellation service() {
        return new RequestRefundCancellation(payments, executions, gateway, reconcile, transactions, Clock.fixed(NOW, ZoneOffset.UTC), successes);
    }
}
