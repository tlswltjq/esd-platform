package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.PgRefundFailureConfirmation;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReceiveRefundRetryTest {
    private static final Instant NOW = Payment.now();
    @Mock PaymentRepository payments;
    @Mock RefundExecutionRepository executions;
    @Mock ProcessedEventGuard inbox;
    @Mock RefundExecutionTraceContexts contexts;
    private Payment paid;
    @Test void anExplicitSuccessorCanBeReceivedOnlyAfterTheRecordedPredecessorFailed() {
        RefundExecution previous = previous(true); RefundExecutionRequest next = successor(previous, previous.getAttemptId());
        when(payments.lockByPaymentId(paid.getPaymentId())).thenReturn(Optional.of(paid));
        when(executions.findFirstByPaymentIdOrderByIdAsc(paid.getPaymentId())).thenReturn(Optional.of(previous));
        when(executions.findFirstByRefundIdOrderByIdAsc(previous.getRefundId())).thenReturn(Optional.of(previous));
        when(executions.findByAttemptId(anyString())).thenAnswer(call -> previous.getAttemptId().equals(call.getArgument(0, String.class)) ? Optional.of(previous) : Optional.empty());
        when(inbox.firstDelivery(next.eventId(), ReceiveRefundExecution.CONSUMER_GROUP, "RefundExecutionRequested")).thenReturn(true);
        when(executions.saveAndFlush(any(RefundExecution.class))).thenAnswer(call -> call.getArgument(0));
        RefundExecution received = service().execute(next);
        assertThat(received.getPreviousAttemptId()).isEqualTo(previous.getAttemptId()); assertThat(received.request()).isEqualTo(next);
        assertThat(previous.getStatus().name()).isEqualTo("FAILED"); assertThat(previous.getFailureConfirmedAt()).isEqualTo(NOW);
    }
    @Test void aPendingPredecessorCannotBeTurnedIntoANewAttemptByAnOrderMessage() {
        RefundExecution previous = previous(false); RefundExecutionRequest next = successor(previous, previous.getAttemptId());
        when(payments.lockByPaymentId(paid.getPaymentId())).thenReturn(Optional.of(paid));
        when(executions.findFirstByPaymentIdOrderByIdAsc(paid.getPaymentId())).thenReturn(Optional.of(previous));
        when(executions.findFirstByRefundIdOrderByIdAsc(previous.getRefundId())).thenReturn(Optional.of(previous));
        when(executions.findByAttemptId(anyString())).thenAnswer(call -> previous.getAttemptId().equals(call.getArgument(0, String.class)) ? Optional.of(previous) : Optional.empty());
        assertThatThrownBy(() -> service().execute(next)).isInstanceOf(RuntimeException.class); verifyNoInteractions(contexts);
    }
    private RefundExecution previous(boolean failed) {
        paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW", "pg-original",
                NOW.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), NOW.minusSeconds(30));
        RefundExecution previous = RefundExecution.receive(paid, execution(paid), NOW);
        if (failed) {
            previous.prepareCancellation(NOW); previous.freezeCancellationBody("original wire body");
            PgRefundCancellationReceipt receipt = new PgRefundCancellationReceipt(new PgRefundLookupRequest(paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(),
                    "KRW", paid.getProviderTransactionId(), paid.getProviderOccurredAt()), previous.getAttemptId(), "cancel-first", PgRefundCancellationReceipt.State.FAILED,
                    NOW, 0, 0, "ESD refund " + previous.getRefundId());
            previous.recordCancellationReceipt(paid, receipt, NOW); previous.confirmFailure(paid, new PgRefundFailureConfirmation(receipt, "a".repeat(64)), id(), NOW);
        }
        return previous;
    }
    private RefundExecutionRequest successor(RefundExecution previous, String predecessor) {
        return new RefundExecutionRequest(id(), NOW.plusSeconds(1), previous.getRefundId(), id(), paid.getOrderNo(), paid.getPaymentId(), paid.getBuyerSubject(),
                paid.getAmount(), "KRW", id(), predecessor);
    }
    private ReceiveRefundExecution service() { return new ReceiveRefundExecution(payments, executions, inbox, contexts, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)); }
}
