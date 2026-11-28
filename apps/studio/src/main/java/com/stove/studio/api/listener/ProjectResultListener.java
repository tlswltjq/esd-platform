package com.stove.studio.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.ProjectReleaseCompletedEvent;
import com.stove.common.contract.payload.ProjectReleaseRejectedEvent;
import com.stove.common.contract.payload.ProjectReviewApprovedEvent;
import com.stove.common.contract.payload.ProjectReviewRejectedEvent;
import com.stove.common.contract.payload.ProjectReviewWithdrawalResolvedEvent;
import com.stove.common.contract.payload.ProjectReleaseWithdrawalResolvedEvent;
import com.stove.studio.core.service.project.RecordProjectWithdrawalResult;
import com.stove.studio.core.service.release.RecordProjectReleaseResult;
import com.stove.studio.core.service.review.RecordProjectReviewResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component
@RequiredArgsConstructor
public class ProjectResultListener {
    private final ObjectMapper mapper;
    private final RecordProjectReviewResult recordProjectReviewResult;
    private final RecordProjectReleaseResult recordProjectReleaseResult;
    private final RecordProjectWithdrawalResult recordProjectWithdrawalResult;

    @KafkaListener(topics = {Topics.REVIEW, Topics.RELEASE}, groupId = RecordProjectReviewResult.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventMessage e = EventMessage.from(record);
        if (e.isType("ProjectReviewWithdrawalResolved")) {
            recordProjectWithdrawalResult.execute(ProjectWithdrawalResultMapper.from(
                    e, e.payloadAs(mapper, ProjectReviewWithdrawalResolvedEvent.class)));
        } else if (e.isType("ProjectReleaseWithdrawalResolved")) {
            recordProjectWithdrawalResult.execute(ProjectWithdrawalResultMapper.from(
                    e, e.payloadAs(mapper, ProjectReleaseWithdrawalResolvedEvent.class)));
        } else if (e.isType("ProjectReviewApproved")) {
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
