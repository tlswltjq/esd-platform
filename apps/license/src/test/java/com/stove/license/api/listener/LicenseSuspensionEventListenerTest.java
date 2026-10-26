package com.stove.license.api.listener;

import static com.stove.license.LicenseFixtures.id;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.license.core.domain.SuspensionRequest;
import com.stove.license.core.service.ApplyLicenseSuspension;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LicenseSuspensionEventListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final ApplyLicenseSuspension apply = mock(ApplyLicenseSuspension.class);
    private final LicenseSuspensionEventListener listener = new LicenseSuspensionEventListener(mapper, apply);

    @Test void onlyValidatedInternalInputReachesTheService() throws Exception {
        LicenseSuspensionRequestedEvent event = event();
        listener.receive(record(event, mapper.writeValueAsString(event)));
        verify(apply).execute(new SuspensionRequest(event.eventId(), event.occurredAt(), event.refundId(),
                event.orderNo(), event.buyerSubject(), event.productId(), event.productCode(), event.paymentId()));
    }

    @Test void envelopeBodyAndKeyMustAgree() {
        LicenseSuspensionRequestedEvent event = event();
        for (EventEnvelope envelope : new EventEnvelope[]{null,
                new EventEnvelope(id(), event.eventType(), event.orderNo(), ""),
                new EventEnvelope(event.eventId(), "Other", event.orderNo(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), id(), "")}) {
            assertThatThrownBy(() -> LicenseSuspensionMapper.from(envelope, event)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(apply);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "occurredAt", "refundId", "orderNo", "buyerSubject", "productId", "productCode", "paymentId"})
    void incompleteRequestsNeverReachTheService(String field) {
        LicenseSuspensionRequestedEvent event = event();
        ObjectNode body = mapper.valueToTree(event);
        body.putNull(field);
        assertThatThrownBy(() -> listener.receive(record(event, body.toString()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(apply);
    }

    @Test void fractionalProductIdsAreNotRounded() {
        LicenseSuspensionRequestedEvent event = event();
        ObjectNode body = mapper.valueToTree(event);
        body.put("productId", 1.5);
        assertThatThrownBy(() -> listener.receive(record(event, body.toString()))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply);
    }

    @Test void unrelatedEventsAreIgnoredAndMissingHeadersAreRejected() {
        LicenseSuspensionRequestedEvent event = event();
        ConsumerRecord<String, String> other = record(event, "{}");
        other.headers().remove(EventHeaders.EVENT_TYPE);
        other.headers().add(EventHeaders.EVENT_TYPE, "Other".getBytes(StandardCharsets.UTF_8));
        listener.receive(other);
        assertThatThrownBy(() -> listener.receive(new ConsumerRecord<>(Topics.ORDER, 0, 0, event.orderNo(), "{}")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply);
    }

    private static LicenseSuspensionRequestedEvent event() {
        return new LicenseSuspensionRequestedEvent(id(), Instant.parse("2026-10-26T01:00:00.123456Z"),
                id(), id(), "usr_buyer", 1L, "Game-1", id());
    }

    private static ConsumerRecord<String, String> record(LicenseSuspensionRequestedEvent event, String body) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.ORDER, 0, 0, event.orderNo(), body);
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
