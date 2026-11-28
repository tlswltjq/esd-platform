package com.stove.catalog.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.service.ReceiveReleaseWithdrawal;
import com.stove.catalog.core.service.ReceiveProjectRelease;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.contract.payload.ProjectReleaseWithdrawalRequestedEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component
@RequiredArgsConstructor
public class StudioReleaseEventListener {
    private final ObjectMapper mapper;
    private final ReceiveProjectRelease receiveProjectRelease;
    private final ReceiveReleaseWithdrawal receiveReleaseWithdrawal;

    @KafkaListener(topics = Topics.STUDIO, groupId = ReceiveProjectRelease.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventMessage message = EventMessage.from(record);
        if (message.isType("ProjectReleaseRequested")) {
            receiveProjectRelease.execute(
                    ReleaseSubmissionMapper.from(message, message.payloadAs(mapper, ProjectReleaseRequestedEvent.class)));
        } else if (message.isType("ProjectReleaseWithdrawalRequested")) {
            receiveReleaseWithdrawal.execute(ReleaseWithdrawalMapper.from(
                    message, message.payloadAs(mapper, ProjectReleaseWithdrawalRequestedEvent.class)));
        }
    }
}
