package com.stove.catalog.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;

import lombok.RequiredArgsConstructor;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StudioReleaseEventListener {
    private final ObjectMapper mapper;
    private final PublishProjectRelease publishProjectRelease;

    @KafkaListener(topics = Topics.STUDIO, groupId = PublishProjectRelease.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        var envelope = EventEnvelope.from(record);
        if (envelope.isType("ProjectReleaseRequested"))
            publishProjectRelease.execute(
                    ReleaseSubmissionMapper.from(envelope, envelope.payloadAs(mapper, ProjectReleaseRequestedEvent.class)));
    }
}
