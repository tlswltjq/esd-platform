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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgRefundCancellationSuccess;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundCancellationDispatch;
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.port.RefundCancellationGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
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
    @Mock RefundCancellationHoldRepository holds;

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

    @Test void redeliveryOfAnUnconfirmedFirstDispatchOnlyLooksUpAndCannotPostAgain() {
        RefundExecution received = fixture();
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.UNCONFIRMED);
        service().executeFirst(received.getAttemptId());
        service().executeFirst(received.getAttemptId());
        verify(gateway, times(1)).send(any(), any(), anyString());
        verify(reconcile, times(2)).execute(received.getAttemptId());
        assertThat(received.getFirstCancellationPreparedAt()).isEqualTo(NOW);
    }

    @Test void preparationWithoutAResponseIsAmbiguousEvenWhenTheProcessStoppedBeforeSending() {
        RefundExecution received = fixture();
        lenient().when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.UNCONFIRMED);
        received.prepareCancellation(NOW);
        received.freezeCancellationBody("original-body");
        service().executeFirst(received.getAttemptId());
        verify(gateway, never()).send(any(), any(), anyString());
        verify(reconcile).execute(received.getAttemptId());
        assertThat(received.getCancellationRequestBody()).isEqualTo("original-body");
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

    @Test void retryDelaysStartAfterTheWholeSupplierAndReconciliationCycleFinishes() {
        RefundExecution received = fixture();
        AtomicReference<Instant> time = new AtomicReference<>(NOW);
        Clock clock = mock(Clock.class); when(clock.instant()).thenAnswer(call -> time.get());
        RequestRefundCancellation service = service(clock);
        when(gateway.send(any(), any(), anyString())).thenAnswer(call -> {
            time.set(time.get().plusSeconds(65)); return RefundCancellationDispatch.UNCONFIRMED;
        });
        doAnswer(call -> { time.set(time.get().plusSeconds(5)); return null; }).when(reconcile).execute(received.getAttemptId());
        service.executeFirst(received.getAttemptId());
        assertThat(received.getCancellationSchedule().getNextAt()).isEqualTo(NOW.plusSeconds(80));
        time.set(NOW.plusSeconds(79)); assertThat(service.claimRetry(received.getAttemptId())).isNull();
        time.set(NOW.plusSeconds(80)); RefundCancellationClaim claim = service.claimRetry(received.getAttemptId());
        service.executeRetry(received.getAttemptId(), claim);
        assertThat(received.getCancellationSchedule().getNextAt()).isEqualTo(NOW.plusSeconds(180));
        assertThat(received.getFirstCancellationPreparedAt()).isEqualTo(NOW);
        assertThat(received.getCancellationRequestBody()).isEqualTo("frozen-wire-body");
        verify(gateway, times(2)).send(any(), any(), anyString());
    }

    @Test void requestedReceiptStopsAutomaticRetriesWhileItsFinalOutcomeRemainsPending() {
        RefundExecution received = fixture(); Payment paid = payments.findByPaymentId(received.getPaymentId()).orElseThrow();
        PgRefundCancellationReceipt receipt = new PgRefundCancellationReceipt(new PgRefundLookupRequest(paid.getPaymentId(), paid.getOrderNo(),
                paid.getAmount(), "KRW", paid.getProviderTransactionId(), paid.getProviderOccurredAt()), received.getAttemptId(),
                "cancel-requested", PgRefundCancellationReceipt.State.REQUESTED, NOW, 0, 0, "ESD refund " + received.getRefundId());
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.received(receipt));
        service().executeFirst(received.getAttemptId());
        assertThat(received.getCancellationSchedule().getNextAt()).isNull();
        assertThat(received.getRefundConfirmedAt()).isNull();
        assertThat(service().claimRetry(received.getAttemptId())).isNull();
    }

    @Test void threeHourBoundaryStopsPostRetriesWithoutResettingPreparationOrStoppingGet() {
        RefundExecution received = fixture();
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.UNCONFIRMED);
        service().executeFirst(received.getAttemptId());
        RequestRefundCancellation later = service(Clock.fixed(NOW.plusSeconds(10800), ZoneOffset.UTC));
        assertThat(later.claimRetry(received.getAttemptId())).isNull();
        assertThat(received.getAutomaticCancellationStoppedAt()).isEqualTo(NOW.plusSeconds(10800));
        assertThat(received.getCancellationSchedule().getNextAt()).isNull();
        assertThat(received.getLookupSchedule().getNextAt()).isNotNull();
        assertThat(received.getFirstCancellationPreparedAt()).isEqualTo(NOW);
        verify(gateway, times(1)).send(any(), any(), anyString());
    }

    @Test void expiredRetryOwnersCannotReachTheSupplierEvenIfTheirQueuedTaskStartsLate() {
        RefundExecution received = fixture();
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.UNCONFIRMED);
        service().executeFirst(received.getAttemptId());
        RequestRefundCancellation atDue = service(Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        RefundCancellationClaim claim = atDue.claimRetry(received.getAttemptId());
        service(Clock.fixed(NOW.plusSeconds(251), ZoneOffset.UTC)).executeRetry(received.getAttemptId(), claim);
        verify(gateway, times(1)).send(any(), any(), anyString());
    }

    @Test void aForbiddenPostCommitsItsHoldBeforeResultLookupAndDoesNotBecomeAFailedRefund() {
        RefundExecution received = fixture();
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.FORBIDDEN);
        service().executeFirst(received.getAttemptId());
        ArgumentCaptor<RefundCancellationHold> recorded = ArgumentCaptor.forClass(RefundCancellationHold.class);
        InOrder order = inOrder(holds, executions, transactions, reconcile);
        order.verify(holds).saveAndFlush(recorded.capture());
        order.verify(executions).flush(); order.verify(transactions).commit(any());
        order.verify(reconcile).execute(received.getAttemptId());
        assertThat(recorded.getValue().isActivated()).isTrue();
        assertThat(recorded.getValue().getReason()).isEqualTo("PG_REQUEST_FORBIDDEN");
        assertThat(received.getActiveCancellationHoldId()).isEqualTo(recorded.getValue().getHoldId());
        assertThat(received.getCancellationSchedule().getNextAt()).isNull();
        assertThat(received.getLookupSchedule().getNextAt()).isNotNull();
        assertThat(received.getFailureEventId()).isNull(); assertThat(received.getRefundConfirmedAt()).isNull();
    }

    @Test void anInvalidPostCommitsItsDistinctHoldBeforeLookupWithoutChangingTheRefundOutcome() {
        RefundExecution received = fixture();
        when(gateway.send(any(), any(), anyString())).thenReturn(RefundCancellationDispatch.INVALID_REQUEST);
        service().executeFirst(received.getAttemptId());
        ArgumentCaptor<RefundCancellationHold> recorded = ArgumentCaptor.forClass(RefundCancellationHold.class);
        InOrder order = inOrder(holds, executions, transactions, reconcile);
        order.verify(holds).saveAndFlush(recorded.capture());
        order.verify(executions).flush(); order.verify(transactions).commit(any());
        order.verify(reconcile).execute(received.getAttemptId());
        assertThat(recorded.getValue().getReason()).isEqualTo("PG_REQUEST_INVALID");
        assertThat(recorded.getValue().isActivated()).isTrue();
        assertThat(received.getActiveCancellationHoldId()).isEqualTo(recorded.getValue().getHoldId());
        assertThat(received.getCancellationSchedule().getNextAt()).isNull();
        assertThat(received.getLookupSchedule().getNextAt()).isNotNull();
        assertThat(received.getFailureEventId()).isNull(); assertThat(received.getRefundConfirmedAt()).isNull();
        assertThat(received.getCancellationRequestBody()).isEqualTo("frozen-wire-body");
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
        return service(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private RequestRefundCancellation service(Clock clock) {
        return new RequestRefundCancellation(payments, executions, gateway, reconcile, transactions, clock, successes,
                Duration.ofMinutes(4), holds);
    }
}
