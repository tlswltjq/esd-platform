package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.RefundFixtures.execution;
import static com.stove.payment.RefundFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.RefundCancellationPermission;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RecordRefundCancellationPreparationTest {
    private static final Instant NOW = Instant.parse("2026-10-27T01:01:00.123456Z");
    @Mock RefundExecutionRepository executions;
    @Mock PlatformTransactionManager transactions;

    @Test void locksAnExistingAttemptAndCommitsBeforeReturningItsOriginalRequestPermission() {
        Payment payment = paid();
        RefundExecution received = RefundExecution.receive(payment, execution(payment), NOW.minusSeconds(10));
        when(executions.lockByAttemptId(received.getAttemptId())).thenReturn(Optional.of(received));
        TransactionStatus status = transaction();
        RefundCancellationPermission result = service().execute(received.getAttemptId());
        assertThat(result.request()).isEqualTo(received.request());
        assertThat(result.firstPreparedAt()).isEqualTo(NOW);
        verify(executions).flush();
        verify(transactions).commit(status);
    }

    @Test void aMissingAttemptCannotCreateAnExecutionOrARequestPermission() {
        transaction();
        assertThatThrownBy(() -> service().execute(id())).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void aFailedCommitDoesNotReturnAPermissionToSendAnExternalRequest() {
        Payment payment = paid();
        RefundExecution received = RefundExecution.receive(payment, execution(payment), NOW);
        when(executions.lockByAttemptId(received.getAttemptId())).thenReturn(Optional.of(received));
        TransactionStatus status = transaction();
        doThrow(new TransientDataAccessResourceException("fixture commit failure")).when(transactions).commit(status);
        assertThatThrownBy(() -> service().execute(received.getAttemptId())).isInstanceOf(TransientDataAccessResourceException.class);
    }

    @Test void anOuterTransactionCannotReceiveAPermissionBeforeItsOwnCommit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service().execute(id())).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(executions, transactions);
        } finally { TransactionSynchronizationManager.clear(); }
    }

    private TransactionStatus transaction() {
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        return status;
    }

    private RecordRefundCancellationPreparation service() {
        return new RecordRefundCancellationPreparation(executions, transactions, Clock.fixed(NOW, ZoneOffset.UTC), new RecordRefundPreparation(mock(OutboxRecorder.class)));
    }
}
