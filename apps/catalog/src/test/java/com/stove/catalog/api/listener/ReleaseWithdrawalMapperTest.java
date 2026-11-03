package com.stove.catalog.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class ReleaseWithdrawalMapperTest {
    @Test
    void acceptsOptionalReasonAndRequiresMatchingEnvelopeAndCompleteTarget() {
        ProjectReleaseWithdrawalRequestedEvent event = ProjectReleaseWithdrawalRequestedEvent.of(new ProjectWithdrawalRequest(1L, 2L, 3L, 4, "GAME", "owner", null));
        EventEnvelope correct = envelope(event, event.eventId(), event.eventType(), event.partitionKey());
        assertThat(ReleaseWithdrawalMapper.from(correct, event).request()).isEqualTo(event.request());
        for (EventEnvelope changed : new EventEnvelope[] {
                null, envelope(event, "other", event.eventType(), event.partitionKey()),
                envelope(event, event.eventId(), "other", event.partitionKey()),
                envelope(event, event.eventId(), event.eventType(), "OTHER")}) {
            assertThatThrownBy(() -> ReleaseWithdrawalMapper.from(changed, event)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> ReleaseWithdrawalMapper.from(correct, new ProjectReleaseWithdrawalRequestedEvent(event.eventId(), null, event.request())))
                .isInstanceOf(BusinessException.class);
        ProjectReleaseWithdrawalRequestedEvent invalid = ProjectReleaseWithdrawalRequestedEvent.of(new ProjectWithdrawalRequest(1L, 2L, 3L, 0, "GAME", "", null));
        assertThatThrownBy(() -> ReleaseWithdrawalMapper.from(envelope(invalid, invalid.eventId(), invalid.eventType(), invalid.partitionKey()), invalid))
                .isInstanceOf(BusinessException.class);
    }

    private EventEnvelope envelope(ProjectReleaseWithdrawalRequestedEvent event, String id, String type, String key) {
        return new EventEnvelope(id, type, key, "");
    }
}
