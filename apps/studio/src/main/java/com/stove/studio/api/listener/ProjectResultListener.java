package com.stove.studio.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.ProjectReleaseCompletedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseRejectedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewApprovedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewRejectedEvent;
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
            recordProjectReviewResult.execute(ProjectResultMapper.from(
                    e, e.payloadAs(mapper, ProjectReviewApprovedEvent.class)));
        } else if (e.isType("ProjectReviewRejected")) {
            recordProjectReviewResult.execute(ProjectResultMapper.from(
                    e, e.payloadAs(mapper, ProjectReviewRejectedEvent.class)));
        } else if (e.isType("ProjectReleaseCompleted")) {
            recordProjectReleaseResult.execute(ProjectResultMapper.from(
                    e, e.payloadAs(mapper, ProjectReleaseCompletedEvent.class)));
        } else if (e.isType("ProjectReleaseRejected")) {
            recordProjectReleaseResult.execute(ProjectResultMapper.from(
                    e, e.payloadAs(mapper, ProjectReleaseRejectedEvent.class)));
        }
    }
}
