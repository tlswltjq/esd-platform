package com.stove.payment.core.service;

import static com.stove.payment.RefundFixtures.paid;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.kafka.contract.payload.RefundExecutionPreparedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.RefundExecution;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecordRefundPreparationTest {
    private static final Instant AT=Instant.parse("2026-10-29T01:00:00.123456Z");
    private final OutboxRecorder outbox=mock(OutboxRecorder.class);
    private final RecordRefundPreparation record=new RecordRefundPreparation(outbox);
    @Test void preparationCreatesOnlyOneDurableFactForTheOriginalOrder() {
        Payment payment=paid(); RefundExecution received=RefundExecution.receive(payment,execution(payment),AT.minusSeconds(1));
        received.prepareCancellation(AT); record.execute(received); record.execute(received);
        verify(outbox,times(1)).record(eq("Payment"),eq(payment.getOrderNo()),any(RefundExecutionPreparedEvent.class));
    }
    @Test void noPreparationTimeCanBeInventedFromReception() {
        Payment payment=paid(); RefundExecution received=RefundExecution.receive(payment,execution(payment),AT);
        assertThatThrownBy(()->record.execute(received)).isInstanceOf(IllegalStateException.class); verifyNoInteractions(outbox);
    }
}
