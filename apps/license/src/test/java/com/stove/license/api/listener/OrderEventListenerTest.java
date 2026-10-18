package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.service.GrantLicense;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static com.stove.license.LicenseFixtures.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderEventListenerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final GrantLicense grant = mock(GrantLicense.class);
    private final OrderEventListener listener = new OrderEventListener(json, grant);

    private ConsumerRecord<String, String> record(OrderPaidEvent e, String type, String body) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.ORDER, 0, 0, e.orderNo(), body);
        record.headers().add(EventHeaders.EVENT_ID, e.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    void mapsOnlyValidatedInternalInput() throws Exception {
        PaidOrder p = paid();
        OrderPaidEvent e = event(p);
        listener.receive(record(e, e.eventType(), json.writeValueAsString(e)));
        verify(grant).execute(p);
    }

    @Test
    void rejectsEnvelopeAndBodyMismatches() {
        OrderPaidEvent e = event(paid());
        for (EventEnvelope env : new EventEnvelope[]{null, new EventEnvelope("wrong", e.eventType(), e.orderNo(), ""),
                new EventEnvelope(e.eventId(), EventType.ORDER_PLACED, e.orderNo(), ""), new EventEnvelope(e.eventId(), e.eventType(), "wrong", "")})
            assertThatThrownBy(() -> LicenseOrderMapper.from(env, e)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> LicenseOrderMapper.from(envelope(e), null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(grant);
    }

    @ParameterizedTest
    @ValueSource(strings = {"productId", "contentVersion", "amount"})
    void fractionalNumbersNeverBecomeValidPurchases(String field) {
        OrderPaidEvent e = event(paid());
        ObjectNode body = json.valueToTree(e);
        body.put(field, 1.5);
        assertThatThrownBy(() -> listener.receive(record(e, e.eventType(), body.toString()))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(grant);
    }

    @Test
    void ignoresOtherContractsAndRejectsMissingHeaders() {
        OrderPaidEvent e = event(paid());
        for (String type : new String[]{EventType.ORDER_PLACED, EventType.PAYMENT_COMPLETED, EventType.ORDER_PAYMENT_FAILED})
            listener.receive(record(e, type, "{}"));
        assertThatThrownBy(() -> listener.receive(new ConsumerRecord<>(Topics.ORDER, 0, 0, e.orderNo(), "{}")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(grant);
    }
}
