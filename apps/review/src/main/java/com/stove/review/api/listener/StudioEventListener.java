package com.stove.review.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalRequestedEvent;
import com.stove.review.core.service.ReceiveProjectReview;
import com.stove.review.core.service.ReceiveReviewWithdrawal;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EventSubscriber(topics = Topics.STUDIO, group = ReceiveProjectReview.CONSUMER_GROUP)
public class StudioEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ReceiveProjectReview receiveProjectReview;
    private final ReceiveReviewWithdrawal receiveReviewWithdrawal;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType("ProjectReviewRequested")) {
            receiveProjectReview.execute(
                    ReviewSubmissionMapper.from(envelope, envelope.payloadAs(mapper, ProjectReviewRequestedEvent.class)));
        } else if (envelope.isType("ProjectReviewWithdrawalRequested")) {
            receiveReviewWithdrawal.execute(ReviewWithdrawalMapper.from(
                    envelope, envelope.payloadAs(mapper, ProjectReviewWithdrawalRequestedEvent.class)));
        }
    }
}
