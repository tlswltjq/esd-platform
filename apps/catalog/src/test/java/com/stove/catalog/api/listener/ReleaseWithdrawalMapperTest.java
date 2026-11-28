package com.stove.catalog.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.ProjectReleaseWithdrawalRequestedEvent;
import com.stove.common.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class ReleaseWithdrawalMapperTest {
    @Test
    void acceptsOptionalReasonAndRequiresMatchingHeaderAndCompleteTarget() {
        ProjectReleaseWithdrawalRequestedEvent event = ProjectReleaseWithdrawalRequestedEvent.of(new ProjectWithdrawalRequest(1L, 2L, 3L, 4, "GAME", "owner", null));
        EventMessage correct = message(event, event.eventId(), event.eventType(), event.partitionKey());
        assertThat(ReleaseWithdrawalMapper.from(correct, event).request()).isEqualTo(event.request());
        for (EventMessage changed : new EventMessage[] {
                null, message(event, "other", event.eventType(), event.partitionKey()),
                message(event, event.eventId(), "other", event.partitionKey()),
                message(event, event.eventId(), event.eventType(), "OTHER")}) {
            assertThatThrownBy(() -> ReleaseWithdrawalMapper.from(changed, event)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> ReleaseWithdrawalMapper.from(correct, new ProjectReleaseWithdrawalRequestedEvent(event.eventId(), null, event.request())))
                .isInstanceOf(BusinessException.class);
        ProjectReleaseWithdrawalRequestedEvent invalid = ProjectReleaseWithdrawalRequestedEvent.of(new ProjectWithdrawalRequest(1L, 2L, 3L, 0, "GAME", "", null));
        assertThatThrownBy(() -> ReleaseWithdrawalMapper.from(message(invalid, invalid.eventId(), invalid.eventType(), invalid.partitionKey()), invalid))
                .isInstanceOf(BusinessException.class);
    }

    private EventMessage message(ProjectReleaseWithdrawalRequestedEvent event, String id, String type, String key) {
        return new EventMessage(id, type, key, "");
    }
}
