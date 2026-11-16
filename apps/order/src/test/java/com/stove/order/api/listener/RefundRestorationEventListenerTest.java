package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundExecutionFailedEvent;
import com.stove.common.kafka.contract.payload.RefundFailureClosedEvent;
import com.stove.common.kafka.contract.payload.LicenseRestoredEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.RefundFailureSource;
import com.stove.order.core.service.ApplyRefundRestoration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class RefundRestorationEventListenerTest {
    private final ApplyRefundRestoration apply = mock(ApplyRefundRestoration.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RefundRestorationEventListener listener = new RefundRestorationEventListener(mapper, apply);
    @Test void aFailureOnTheLicenseTopicCannotAuthorizeRestoration() throws Exception {
        RefundExecutionFailedEvent event = new RefundExecutionFailedEvent(id(), Instant.parse("2026-10-27T01:02:00Z"), id(), id(), id(), id(),
                id(), "buyer", 9000, "KRW", "pg-original", "cancel-own", "a".repeat(64));
        assertThatThrownBy(() -> listener.receive(record(event, Topics.LICENSE))).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(apply);
    }
    /** 결정 148: 운영자 실패 종결도 Payment 토픽에서만 받고, 같은 실패 유스케이스로 넘긴다. */
    @Test void anOperatorFailureClosureIsReceivedOnlyFromThePaymentTopicAndAppliedAsAFailure() throws Exception {
        RefundFailureClosedEvent event = new RefundFailureClosedEvent(id(), Instant.parse("2026-10-27T01:02:00Z"), id(), id(), id(), id(),
                id(), "buyer", 9000, "KRW", "pg-original", id(), "PAID", Instant.parse("2026-10-27T01:01:00Z"), "b".repeat(64), null);
        assertThatThrownBy(() -> listener.receive(record(event, Topics.LICENSE))).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(apply);
        listener.receive(record(event, Topics.PAYMENT));
        verify(apply).execute(argThat((RefundFailureConfirmation input) -> input.source() == RefundFailureSource.OPERATOR_FAILURE_CLOSED
                && input.closureId().equals(event.closureId()) && input.eventId().equals(event.eventId())));
    }
    @Test void aRestorationClaimOnThePaymentTopicCannotCompleteTheWorkflow() throws Exception {
        LicenseRestoredEvent event = new LicenseRestoredEvent(id(), Instant.parse("2026-10-27T01:03:00Z"), id(), id(), id(), id(), id(), id(),
                "buyer", 1L, "PRODUCT-1", "payment-1", id(), id(), false);
        assertThatThrownBy(() -> listener.receive(record(event, Topics.PAYMENT))).isInstanceOf(RefundCompletionRejected.class);
        verifyNoInteractions(apply);
    }
    private ConsumerRecord<String, String> record(DomainEvent event, String topic) throws Exception {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0, event.partitionKey(), mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8)); return record;
    }
}
