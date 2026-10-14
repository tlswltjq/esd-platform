package com.stove.studio.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.*;
import com.stove.studio.core.service.release.RecordProjectReleaseResult;
import com.stove.studio.core.service.review.RecordProjectReviewResult;

import lombok.RequiredArgsConstructor;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProjectResultListener {
    private final ObjectMapper mapper;
    private final RecordProjectReviewResult recordProjectReviewResult;
    private final RecordProjectReleaseResult recordProjectReleaseResult;

    @KafkaListener(
            topics = {Topics.REVIEW, Topics.RELEASE},
            groupId = RecordProjectReviewResult.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope e = EventEnvelope.from(record);
        if (e.isType("ProjectReviewApproved")) {
            var v = e.payloadAs(mapper, ProjectReviewApprovedEvent.class);
            recordProjectReviewResult.execute(
                    e.eventId(),
                    e.eventType(),
                    v.reviewRequestId(),
                    v.projectId(),
                    v.contentVersion(),
                    true,
                    null,
                    v.actor());
        } else if (e.isType("ProjectReviewRejected")) {
            var v = e.payloadAs(mapper, ProjectReviewRejectedEvent.class);
            recordProjectReviewResult.execute(
                    e.eventId(),
                    e.eventType(),
                    v.reviewRequestId(),
                    v.projectId(),
                    v.contentVersion(),
                    false,
                    v.feedback(),
                    v.actor());
        } else if (e.isType("ProjectReleaseCompleted")) {
            var v = e.payloadAs(mapper, ProjectReleaseCompletedEvent.class);
            recordProjectReleaseResult.execute(
                    e.eventId(),
                    e.eventType(),
                    v.releaseRequestId(),
                    v.projectId(),
                    v.contentVersion(),
                    true,
                    null,
                    v.requestEventId());
        } else if (e.isType("ProjectReleaseRejected")) {
            var v = e.payloadAs(mapper, ProjectReleaseRejectedEvent.class);
            recordProjectReleaseResult.execute(
                    e.eventId(),
                    e.eventType(),
                    v.releaseRequestId(),
                    v.projectId(),
                    v.contentVersion(),
                    false,
                    v.reason(),
                    v.requestEventId());
        }
    }
}
