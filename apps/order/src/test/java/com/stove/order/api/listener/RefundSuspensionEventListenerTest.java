package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseSuspendedEvent;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.service.ApplyRefundSuspension;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefundSuspensionEventListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final ApplyRefundSuspension apply = mock(ApplyRefundSuspension.class);
    private final RefundSuspensionEventListener listener = new RefundSuspensionEventListener(mapper, apply);

    @Test void mapsAValidCompletionToInternalInput() throws Exception {
        LicenseSuspendedEvent event = event();

        listener.receive(record(event, event.eventType(), mapper.writeValueAsString(event)));

        verify(apply).execute(new LicenseSuspensionConfirmation(event.eventId(), event.occurredAt(), event.requestEventId(),
                event.refundId(), event.licenseId(), event.orderNo(), event.buyerSubject(), event.productId(),
                event.productCode(), event.paymentId()));
    }

    @Test void envelopeMismatchesAreRejectedBeforeBusinessWork() {
        LicenseSuspendedEvent event = event();
        for (EventEnvelope envelope : new EventEnvelope[]{null,
                new EventEnvelope(id(), event.eventType(), event.orderNo(), ""),
                new EventEnvelope(event.eventId(), EventType.LICENSE_GRANTED, event.orderNo(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), id(), "")}) {
            assertThatThrownBy(() -> RefundSuspensionMapper.from(envelope, event)).isInstanceOf(RefundCompletionRejected.class);
        }
        assertThatThrownBy(() -> RefundSuspensionMapper.from(
                new EventEnvelope(event.eventId(), event.eventType(), event.orderNo(), ""), null))
                .isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(apply);
    }

    @ParameterizedTest @ValueSource(strings = {"eventId", "occurredAt", "requestEventId", "refundId", "licenseId",
            "orderNo", "buyerSubject", "productId", "productCode", "paymentId"})
    void missingEvidenceIsClassifiedForQuarantine(String field) {
        LicenseSuspendedEvent event = event();
        ObjectNode body = mapper.valueToTree(event);
        body.putNull(field);

        assertThatThrownBy(() -> listener.receive(record(event, event.eventType(), body.toString())))
                .isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(apply);
    }

    @ParameterizedTest @ValueSource(strings = {"productId", "occurredAt", "refundId", "licenseId", "buyerSubject", "paymentId"})
    void invalidValuesCannotReachBusinessWork(String field) {
        LicenseSuspendedEvent event = event();
        ObjectNode body = mapper.valueToTree(event);
        if (field.equals("productId")) body.put(field, 1.5);
        else if (field.equals("occurredAt")) body.put(field, "2026-10-26T01:00:00.123456001Z");
        else body.put(field, " ");

        assertThatThrownBy(() -> listener.receive(record(event, event.eventType(), body.toString())))
                .isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(apply);
    }

    @Test void malformedJsonAndMissingHeadersAreClassifiedForQuarantine() {
        LicenseSuspendedEvent event = event();
        assertThatThrownBy(() -> listener.receive(record(event, event.eventType(), "{")))
                .isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> listener.receive(new ConsumerRecord<>(Topics.LICENSE, 0, 0, event.orderNo(), "{}")))
                .isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(apply);
    }

    @Test void unrelatedEventsDoNotInvokeTheRefundService() {
        LicenseSuspendedEvent event = event();
        listener.receive(record(event, EventType.LICENSE_GRANTED, "{}"));
        verifyNoInteractions(apply);
    }

    @Test void aFailureInsideTheServiceIsNotMisclassifiedAsMalformedInput() throws Exception {
        LicenseSuspendedEvent event = event();
        doThrow(new IllegalStateException("outbox insert unavailable")).when(apply).execute(any(LicenseSuspensionConfirmation.class));
        assertThatThrownBy(() -> listener.receive(record(event, event.eventType(), mapper.writeValueAsString(event))))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("outbox insert unavailable");
    }

    private static LicenseSuspendedEvent event() {
        return new LicenseSuspendedEvent(id(), Instant.parse("2026-10-26T01:00:00.123456Z"), id(), id(), id(), id(),
                "usr_buyer", 1L, "Game-1", id());
    }

    private static ConsumerRecord<String, String> record(LicenseSuspendedEvent event, String type, String body) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.LICENSE, 0, 0, event.orderNo(), body);
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
