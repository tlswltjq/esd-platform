package com.stove.catalog.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.service.ReceiveReleaseWithdrawal;
import com.stove.catalog.core.service.ReceiveProjectRelease;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalRequestedEvent;

import lombok.RequiredArgsConstructor;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StudioReleaseEventListener {
    private final ObjectMapper mapper;
    private final ReceiveProjectRelease receiveProjectRelease;
    private final ReceiveReleaseWithdrawal receiveReleaseWithdrawal;

    @KafkaListener(topics = Topics.STUDIO, groupId = ReceiveProjectRelease.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType("ProjectReleaseRequested")) {
            receiveProjectRelease.execute(
                    ReleaseSubmissionMapper.from(envelope, envelope.payloadAs(mapper, ProjectReleaseRequestedEvent.class)));
        } else if (envelope.isType("ProjectReleaseWithdrawalRequested")) {
            receiveReleaseWithdrawal.execute(ReleaseWithdrawalMapper.from(
                    envelope, envelope.payloadAs(mapper, ProjectReleaseWithdrawalRequestedEvent.class)));
        }
    }
}
