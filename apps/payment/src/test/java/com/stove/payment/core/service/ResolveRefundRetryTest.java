package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.domain.RefundExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ResolveRefundRetryTest {
    private static final Instant NOW = Instant.parse("2026-10-27T01:00:00Z");
    private final RefundExecutionRepository executions = mock(RefundExecutionRepository.class);
    private final RequestRefundCancellation cancellations = mock(RequestRefundCancellation.class);

    @Test void claimsBeforeQueueingAndOnlyRunsHttpInsideTheSubmittedTask() {
        String attempt = id(); RefundCancellationClaim claim = new RefundCancellationClaim(id(), NOW);
        when(executions.findRetryCandidates(any(), any())).thenReturn(List.of(attempt));
        when(cancellations.claimRetry(attempt)).thenReturn(claim);
        List<Runnable> queued = new ArrayList<>();
        service(queued::add).execute();
        verify(cancellations).claimRetry(attempt);
        verify(cancellations, never()).executeRetry(attempt, claim);
        assertThat(queued).hasSize(1);
        queued.getFirst().run(); verify(cancellations).executeRetry(attempt, claim);
    }

    @Test void rejectedSubmissionReleasesTheClaimWithoutAdvancingItsSchedule() {
        String attempt = id(); RefundCancellationClaim claim = new RefundCancellationClaim(id(), NOW);
        when(executions.findRetryCandidates(any(), any())).thenReturn(List.of(attempt));
        when(cancellations.claimRetry(attempt)).thenReturn(claim);
        service(task -> { throw new RejectedExecutionException("full"); }).execute();
        verify(cancellations).abandonRetry(attempt, claim);
        verify(cancellations, never()).executeRetry(attempt, claim);
    }

    @Test void noClaimMeansNoHttpSubmission() {
        String attempt = id(); when(executions.findRetryCandidates(any(), any())).thenReturn(List.of(attempt));
        List<Runnable> queued = new ArrayList<>(); service(queued::add).execute();
        assertThat(queued).isEmpty();
    }

    @Test void oneSupplierFailureDoesNotPreventAnotherDueAttemptFromBeingProcessed() {
        String first = id(); String second = id();
        RefundCancellationClaim a = new RefundCancellationClaim(id(), NOW); RefundCancellationClaim b = new RefundCancellationClaim(id(), NOW);
        when(executions.findRetryCandidates(any(), any())).thenReturn(List.of(first, second));
        when(cancellations.claimRetry(first)).thenReturn(a); when(cancellations.claimRetry(second)).thenReturn(b);
        doThrow(new PgUnavailable("unknown")).when(cancellations).executeRetry(first, a);
        service(Runnable::run).execute();
        verify(cancellations).executeRetry(second, b);
    }

    @Test void outerTransactionsCannotEncloseTheScannerOrSupplierWork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service(Runnable::run).execute()).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(executions, cancellations);
        } finally { TransactionSynchronizationManager.clear(); }
    }

    private ResolveRefundRetry service(Executor executor) {
        return new ResolveRefundRetry(executions, cancellations, Clock.fixed(NOW, ZoneOffset.UTC), executor, 20);
    }
}
