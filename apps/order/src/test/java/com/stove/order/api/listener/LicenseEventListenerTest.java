package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.stove.common.kafka.contract.payload.LicenseGrantedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.service.ApplyLicenseGrant;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LicenseEventListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final ApplyLicenseGrant apply = mock(ApplyLicenseGrant.class);
    private final LicenseEventListener listener = new LicenseEventListener(mapper, apply);

    @Test
    void mapsTheConfirmedGrantToInternalInput() throws Exception {
        LicenseGrantedEvent event = event();
        listener.receive(record(event, event.eventType(), mapper.writeValueAsString(event)));
        verify(apply).execute(new LicenseGrant(event.eventId(), event.occurredAt(), event.licenseId(),
                event.orderNo(), event.buyerSubject(), event.productId(), event.productCode(), event.paymentId()));
    }

    @Test
    void rejectsEnvelopeMismatchesBeforeAnyBusinessWork() {
        LicenseGrantedEvent event = event();
        for (EventEnvelope envelope : new EventEnvelope[]{null,
                new EventEnvelope(id(), event.eventType(), event.orderNo(), ""),
                new EventEnvelope(event.eventId(), EventType.ORDER_PAID, event.orderNo(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), id(), "")}) {
            assertThatThrownBy(() -> LicenseGrantMapper.from(envelope, event)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> LicenseGrantMapper.from(
                new EventEnvelope(event.eventId(), event.eventType(), event.orderNo(), ""), null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(apply);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "occurredAt", "licenseId", "orderNo", "buyerSubject", "productId", "productCode", "paymentId"})
    void rejectsMissingPurchaseAndEventEvidence(String field) throws Exception {
        LicenseGrantedEvent event = event();
        ObjectNode body = mapper.valueToTree(event);
        body.putNull(field);
        assertThatThrownBy(() -> listener.receive(record(event, event.eventType(), body.toString())))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(apply);
    }

    @Test
    void fractionalProductIdsAreNotSilentlyRounded() {
        LicenseGrantedEvent event = event();
        ObjectNode body = mapper.valueToTree(event);
        body.put("productId", 1.5);
        assertThatThrownBy(() -> listener.receive(record(event, event.eventType(), body.toString())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply);
    }

    @Test
    void unrelatedEventsAreIgnoredAndMissingHeadersAreRejected() {
        LicenseGrantedEvent event = event();
        listener.receive(record(event, "Unrelated", "{}"));
        assertThatThrownBy(() -> listener.receive(new ConsumerRecord<>(Topics.LICENSE, 0, 0, event.orderNo(), "{}")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(apply);
    }

    private static LicenseGrantedEvent event() {
        return new LicenseGrantedEvent(id(), Instant.parse("2026-10-26T01:00:00.123456Z"), id(), id(),
                "usr_buyer", 1L, "Game-1", id());
    }

    private static ConsumerRecord<String, String> record(LicenseGrantedEvent event, String type, String body) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.LICENSE, 0, 0, event.orderNo(), body);
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
