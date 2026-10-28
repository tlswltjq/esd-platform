package com.stove.payment.api.listener;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.RefundFixtures.event;
import static com.stove.payment.RefundFixtures.execution;
import static com.stove.payment.RefundFixtures.paid;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RequestFirstRefundCancellation;
import java.util.Optional;
import org.mockito.InOrder;
import org.springframework.dao.TransientDataAccessResourceException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefundExecutionEventListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final ReceiveRefundExecution receive = mock(ReceiveRefundExecution.class);
    private final RequestFirstRefundCancellation cancellation = mock(RequestFirstRefundCancellation.class);
    private final RefundExecutionEventListener listener = new RefundExecutionEventListener(mapper, receive, Optional.of(cancellation));

    @Test void everyValidDeliveryContinuesAfterReceiptIncludingAnAlreadyReceivedEvent() throws Exception {
        RefundExecutionRequest input = execution(paid());
        RefundExecutionRequestedEvent event = event(input);
        ConsumerRecord<String, String> record = record(event, mapper.writeValueAsString(event));
        listener.receive(record);
        listener.receive(record);
        InOrder calls = inOrder(receive, cancellation);
        calls.verify(receive).execute(input, PaymentTraceContext.NONE);
        calls.verify(cancellation).execute(input.attemptId());
        calls.verify(receive).execute(input, PaymentTraceContext.NONE);
        calls.verify(cancellation).execute(input.attemptId());
    }

    @Test void receiptFailureCannotCallTheSupplierAndMustRemainARetryableDelivery() throws Exception {
        RefundExecutionRequest input = execution(paid());
        RefundExecutionRequestedEvent event = event(input);
        doThrow(new TransientDataAccessResourceException("receipt commit failed"))
                .when(receive).execute(input, PaymentTraceContext.NONE);
        assertThatThrownBy(() -> listener.receive(record(event, mapper.writeValueAsString(event))))
                .isInstanceOf(TransientDataAccessResourceException.class);
        verifyNoInteractions(cancellation);
    }

    @Test void disabledDispatchStillReceivesDurably() throws Exception {
        RefundExecutionRequest input = execution(paid());
        RefundExecutionRequestedEvent event = event(input);
        new RefundExecutionEventListener(mapper, receive, Optional.empty()).receive(record(event, mapper.writeValueAsString(event)));
        verify(receive).execute(input, PaymentTraceContext.NONE);
        verifyNoInteractions(cancellation);
    }

    @Test void mapsTheRequestToInternalInputWithOptionalTraceParent() throws Exception {
        RefundExecutionRequest input = execution(paid());
        RefundExecutionRequestedEvent event = event(input);
        ConsumerRecord<String, String> record = record(event, mapper.writeValueAsString(event));
        listener.receive(record);
        verify(receive).execute(input, PaymentTraceContext.NONE);
        String parent = "00-11111111111111111111111111111111-2222222222222222-01";
        record.headers().add(EventHeaders.TRACE_PARENT, parent.getBytes(StandardCharsets.UTF_8));
        listener.receive(record);
        verify(receive).execute(input, new PaymentTraceContext(parent));
    }

    @Test void missingOrMismatchedEnvelopeCannotReachTheUseCase() {
        RefundExecutionRequestedEvent event = event(execution(paid()));
        for (EventEnvelope envelope : new EventEnvelope[]{null,
                new EventEnvelope(id(), event.eventType(), event.orderNo(), ""),
                new EventEnvelope(event.eventId(), EventType.ORDER_PLACED, event.orderNo(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), id(), "")}) {
            assertThatThrownBy(() -> RefundExecutionMapper.from(envelope, event)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> RefundExecutionMapper.from(
                new EventEnvelope(event.eventId(), event.eventType(), event.orderNo(), ""), null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> listener.receive(new ConsumerRecord<>(Topics.ORDER, 0, 0, event.orderNo(), "{}")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(receive);
    }

    @ParameterizedTest @ValueSource(strings = {"eventId", "occurredAt", "refundId", "attemptId", "orderNo", "paymentId",
            "buyerSubject", "amount", "currency", "licenseSuspensionEventId"})
    void missingFinancialOrExecutionFactsAreRejected(String field) {
        RefundExecutionRequestedEvent event = event(execution(paid()));
        ObjectNode body = mapper.valueToTree(event);
        body.remove(field);
        assertThatThrownBy(() -> listener.receive(record(event, body.toString()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(receive);
    }

    @Test void fractionalAmountsCannotBeTruncatedIntoAValidRefund() {
        RefundExecutionRequestedEvent event = event(execution(paid()));
        ObjectNode body = mapper.valueToTree(event);
        body.put("amount", 10000.5);
        assertThatThrownBy(() -> listener.receive(record(event, body.toString()))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(receive);
    }

    @ParameterizedTest @ValueSource(strings = {"refundId", "attemptId", "currency", "amount", "occurredAt"})
    void invalidValuesCannotBecomeAnInternalRequest(String field) {
        RefundExecutionRequestedEvent event = event(execution(paid()));
        ObjectNode body = mapper.valueToTree(event);
        if (field.equals("amount")) body.put(field, 0);
        else if (field.equals("occurredAt")) body.put(field, "2026-10-27T01:00:00.123456001Z");
        else body.put(field, field.equals("currency") ? "USD" : "bad-id");
        assertThatThrownBy(() -> listener.receive(record(event, body.toString()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(receive);
    }

    @Test void anInvalidTraceHeaderDoesNotRejectTheBusinessRequest() throws Exception {
        RefundExecutionRequest input = execution(paid());
        RefundExecutionRequestedEvent event = event(input);
        ConsumerRecord<String, String> record = record(event, mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.TRACE_PARENT, "invalid".getBytes(StandardCharsets.UTF_8));
        listener.receive(record);
        verify(receive).execute(input, PaymentTraceContext.NONE);
    }

    @Test void otherOrderEventsDoNotStartARefund() {
        RefundExecutionRequestedEvent event = event(execution(paid()));
        ConsumerRecord<String, String> record = record(event, "{}");
        record.headers().remove(EventHeaders.EVENT_TYPE);
        record.headers().add(EventHeaders.EVENT_TYPE, EventType.ORDER_PLACED.getBytes(StandardCharsets.UTF_8));
        listener.receive(record);
        verifyNoInteractions(receive);
    }

    private static ConsumerRecord<String, String> record(RefundExecutionRequestedEvent event, String body) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.ORDER, 0, 0, event.orderNo(), body);
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
