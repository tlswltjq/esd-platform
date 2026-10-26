package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.RefundFixtures.execution;
import static com.stove.payment.RefundFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentTraceContext;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;

@ExtendWith(MockitoExtension.class)
class ReceiveRefundExecutionTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T01:01:00.123456Z");
    private static final PaymentTraceContext PARENT = new PaymentTraceContext("00-11111111111111111111111111111111-2222222222222222-01");
    @Mock PaymentRepository payments;
    @Mock RefundExecutionRepository executions;
    @Mock ProcessedEventGuard inbox;
    @Mock RefundExecutionTraceContexts contexts;

    @Test void aValidRequestIsStoredWithItsOriginalIdentityAndTraceParent() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        when(payments.lockByPaymentId(input.paymentId())).thenReturn(Optional.of(paid));
        when(inbox.firstDelivery(input.eventId(), ReceiveRefundExecution.CONSUMER_GROUP, "RefundExecutionRequested")).thenReturn(true);
        when(executions.saveAndFlush(any(RefundExecution.class))).thenAnswer(call -> call.getArgument(0));

        RefundExecution received = service().execute(input, PARENT);

        assertThat(received.request()).isEqualTo(input);
        assertThat(received.getReceivedAt()).isEqualTo(RECEIVED_AT);
        ArgumentCaptor<RefundExecution> saved = ArgumentCaptor.forClass(RefundExecution.class);
        verify(executions).saveAndFlush(saved.capture());
        assertThat(saved.getValue()).isSameAs(received);
        verify(contexts).save(input.attemptId(), PARENT, RECEIVED_AT);
    }

    @Test void duplicateDeliveryReturnsTheOriginalReceiptWithoutRewritingItsTraceOrTime() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        RefundExecution original = RefundExecution.receive(paid, input, RECEIVED_AT.minusSeconds(10));
        when(payments.lockByPaymentId(input.paymentId())).thenReturn(Optional.of(paid));
        when(executions.findByAttemptId(input.attemptId())).thenReturn(Optional.of(original));

        assertThat(service().execute(input, PARENT)).isSameAs(original);

        assertThat(original.getReceivedAt()).isEqualTo(RECEIVED_AT.minusSeconds(10));
        verify(executions, never()).saveAndFlush(any(RefundExecution.class));
        verifyNoInteractions(contexts);
    }

    @ParameterizedTest @ValueSource(strings = {"event", "time", "refund", "suspension"})
    void changedRedeliveryCannotReachInboxOrReplaceTheOriginalReceipt(String field) {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        RefundExecution original = RefundExecution.receive(paid, input, RECEIVED_AT);
        RefundExecutionRequest changed = new RefundExecutionRequest(field.equals("event") ? id() : input.eventId(),
                field.equals("time") ? input.occurredAt().plusSeconds(1) : input.occurredAt(),
                field.equals("refund") ? id() : input.refundId(), input.attemptId(), input.orderNo(), input.paymentId(),
                input.buyerSubject(), input.amount(), input.currency(), field.equals("suspension") ? id() : input.licenseSuspensionEventId());
        when(payments.lockByPaymentId(input.paymentId())).thenReturn(Optional.of(paid));
        when(executions.findByAttemptId(input.attemptId())).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service().execute(changed)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(inbox, contexts);
        assertThat(original.request()).isEqualTo(input);
    }

    @Test void aMissingPaymentCannotBeInventedFromTheOrderRequest() {
        RefundExecutionRequest input = execution(paid());
        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(executions, inbox, contexts);
    }

    @Test void paymentFactsAreCheckedBeforeAnExistingInboxIsTrusted() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        RefundExecutionRequest partial = new RefundExecutionRequest(input.eventId(), input.occurredAt(), input.refundId(), input.attemptId(),
                input.orderNo(), input.paymentId(), input.buyerSubject(), 9000, input.currency(), input.licenseSuspensionEventId());
        when(payments.lockByPaymentId(input.paymentId())).thenReturn(Optional.of(paid));
        assertThatThrownBy(() -> service().execute(partial)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(executions, inbox, contexts);
    }

    @ParameterizedTest @ValueSource(strings = {"sameRefund", "otherRefund"})
    void anotherAttemptCannotStartWhileTheFirstPaymentRefundIsPending(String kind) {
        Payment paid = paid();
        RefundExecutionRequest first = execution(paid);
        RefundExecutionRequest next = new RefundExecutionRequest(id(), first.occurredAt(), kind.equals("sameRefund") ? first.refundId() : id(),
                id(), first.orderNo(), first.paymentId(), first.buyerSubject(), first.amount(), first.currency(), id());
        when(payments.lockByPaymentId(next.paymentId())).thenReturn(Optional.of(paid));
        when(executions.findFirstByPaymentIdOrderByIdAsc(next.paymentId()))
                .thenReturn(Optional.of(RefundExecution.receive(paid, first, RECEIVED_AT)));
        assertThatThrownBy(() -> service().execute(next)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inbox, contexts);
    }

    @Test void oneRefundIdentityCannotBeReboundToAnotherConfirmedPayment() {
        Payment first = paid();
        Payment second = paid();
        RefundExecutionRequest old = execution(first);
        RefundExecutionRequest input = new RefundExecutionRequest(id(), old.occurredAt(), old.refundId(), id(), second.getOrderNo(),
                second.getPaymentId(), second.getBuyerSubject(), second.getAmount(), second.getCurrency(), id());
        when(payments.lockByPaymentId(second.getPaymentId())).thenReturn(Optional.of(second));
        when(executions.findFirstByRefundIdOrderByIdAsc(old.refundId()))
                .thenReturn(Optional.of(RefundExecution.receive(first, old, RECEIVED_AT)));
        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inbox, contexts);
    }

    @Test void aRequestEventIdentityCannotBeUsedForAnotherAttempt() {
        Payment paid = paid();
        RefundExecutionRequest old = execution(paid);
        RefundExecutionRequest input = new RefundExecutionRequest(old.eventId(), old.occurredAt(), old.refundId(), id(), old.orderNo(),
                old.paymentId(), old.buyerSubject(), old.amount(), old.currency(), id());
        when(payments.lockByPaymentId(input.paymentId())).thenReturn(Optional.of(paid));
        when(executions.findByRequestEventId(input.eventId())).thenReturn(Optional.of(RefundExecution.receive(paid, old, RECEIVED_AT)));
        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inbox, contexts);
    }

    @Test void anInboxWithoutItsReceiptCannotBeAcceptedAsADuplicate() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        when(payments.lockByPaymentId(input.paymentId())).thenReturn(Optional.of(paid));
        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(BusinessException.class);
        verify(executions, never()).saveAndFlush(any(RefundExecution.class));
        verifyNoInteractions(contexts);
    }

    @Test void aReceiptWithoutItsInboxCannotHaveItsMarkerRecreatedSilently() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        when(payments.lockByPaymentId(input.paymentId())).thenReturn(Optional.of(paid));
        when(executions.findByAttemptId(input.attemptId())).thenReturn(Optional.of(RefundExecution.receive(paid, input, RECEIVED_AT)));
        when(inbox.firstDelivery(input.eventId(), ReceiveRefundExecution.CONSUMER_GROUP, "RefundExecutionRequested")).thenReturn(true);
        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(contexts);
    }

    @Test void anInfrastructureFailureIsNotConvertedIntoAFinalRefundFailure() {
        RefundExecutionRequest input = execution(paid());
        when(payments.lockByPaymentId(input.paymentId())).thenThrow(new TransientDataAccessResourceException("DB unavailable"));
        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(TransientDataAccessResourceException.class);
        verifyNoInteractions(executions, inbox, contexts);
    }

    private ReceiveRefundExecution service() {
        return new ReceiveRefundExecution(payments, executions, inbox, contexts, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }
}
