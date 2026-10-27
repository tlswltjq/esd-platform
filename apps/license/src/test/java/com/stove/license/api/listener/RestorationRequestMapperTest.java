package com.stove.license.api.listener;

import static com.stove.license.LicenseFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestorationRequestMapperTest {
    private final LicenseRestorationRequestedEvent event = new LicenseRestorationRequestedEvent(id(), Instant.parse("2026-10-27T01:02:00Z"),
            id(), id(), id(), id(), id(), "buyer", 1L, "PRODUCT-1", "payment-1", id(), id());
    @Test void mapsAllTheFailureAndActualSuspensionIdentities() {
        assertThat(RestorationRequestMapper.from(new EventEnvelope(event.eventId(), event.eventType(), event.orderNo(), ""), event).suspensionEventId())
                .isEqualTo(event.suspensionEventId());
    }
    @Test void changedEnvelopeIdentityTypeOrKeyCannotAuthorizeRestoration() {
        for (EventEnvelope invalid : List.of(new EventEnvelope(id(), event.eventType(), event.orderNo(), ""),
                new EventEnvelope(event.eventId(), "Other", event.orderNo(), ""), new EventEnvelope(event.eventId(), event.eventType(), id(), "")))
            assertThatThrownBy(() -> RestorationRequestMapper.from(invalid, event)).isInstanceOf(BusinessException.class);
    }
}
