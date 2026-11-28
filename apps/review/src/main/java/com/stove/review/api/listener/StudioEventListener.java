package com.stove.review.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.contract.payload.ProjectReviewWithdrawalRequestedEvent;
import com.stove.review.core.service.ReceiveProjectReview;
import com.stove.review.core.service.ReceiveReviewWithdrawal;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component
@RequiredArgsConstructor
public class StudioEventListener {
    private final ObjectMapper mapper;
    private final ReceiveProjectReview receiveProjectReview;
    private final ReceiveReviewWithdrawal receiveReviewWithdrawal;

    @KafkaListener(topics = Topics.STUDIO, groupId = ReceiveProjectReview.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventMessage message = EventMessage.from(record);
        if (message.isType("ProjectReviewRequested")) {
            receiveProjectReview.execute(
                    ReviewSubmissionMapper.from(message, message.payloadAs(mapper, ProjectReviewRequestedEvent.class)));
        } else if (message.isType("ProjectReviewWithdrawalRequested")) {
            receiveReviewWithdrawal.execute(ReviewWithdrawalMapper.from(
                    message, message.payloadAs(mapper, ProjectReviewWithdrawalRequestedEvent.class)));
        }
    }
}
