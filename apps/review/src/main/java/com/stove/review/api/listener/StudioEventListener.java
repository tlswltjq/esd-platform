package com.stove.review.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.review.core.service.ReceiveProjectReview;

import lombok.RequiredArgsConstructor;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StudioEventListener {
    private final ObjectMapper mapper;
    private final ReceiveProjectReview receiveProjectReview;

    @KafkaListener(topics = Topics.STUDIO, groupId = ReceiveProjectReview.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        var envelope = EventEnvelope.from(record);
        if (envelope.isType("ProjectReviewRequested"))
            receiveProjectReview.execute(
                    envelope, envelope.payloadAs(mapper, ProjectReviewRequestedEvent.class));
    }
}
