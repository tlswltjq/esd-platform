package com.stove.catalog.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.catalog.core.service.ReceiveReleaseWithdrawal;
import com.stove.catalog.core.service.ReceiveProjectRelease;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.contract.payload.ProjectReleaseWithdrawalRequestedEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EventSubscriber(topics = Topics.STUDIO, group = ReceiveProjectRelease.CONSUMER_GROUP)
public class StudioReleaseEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ReceiveProjectRelease receiveProjectRelease;
    private final ReceiveReleaseWithdrawal receiveReleaseWithdrawal;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType("ProjectReleaseRequested")) {
            receiveProjectRelease.execute(
                    ReleaseSubmissionMapper.from(envelope, envelope.payloadAs(mapper, ProjectReleaseRequestedEvent.class)));
        } else if (envelope.isType("ProjectReleaseWithdrawalRequested")) {
            receiveReleaseWithdrawal.execute(ReleaseWithdrawalMapper.from(
                    envelope, envelope.payloadAs(mapper, ProjectReleaseWithdrawalRequestedEvent.class)));
        }
    }
}
