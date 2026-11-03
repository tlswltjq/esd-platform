package com.stove.studio.core.service.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.domain.build.BuildRepository;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.domain.project.ProjectRequestCommand;
import com.stove.studio.core.domain.project.ProjectRequestCommandRepository;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.domain.project.SubmissionCommand;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequestRepository;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.project.ReadProjects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RequestProjectReview {
    private final ProjectRepository projects;
    private final BuildRepository builds;
    private final ProjectReviewRequestRepository reviews;
    private final OutboxRecorder outbox;
    private final ObjectMapper mapper;
    private final BuildStorage storage;
    private final ReadProjects readProjects;
    private final ProjectRequestCommandRepository commands;

    public ProjectReviewRequest execute(Long id, String subject, SubmissionCommand input) {
        Project p = projects.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        p.requireOwner(subject);
        ProjectRequestCommand previous = commands.findByProjectIdAndKindAndRequestKey(
                id, ProjectRequestKind.REQUEST_REVIEW, input.requestId()).orElse(null);
        if (previous != null) {
            previous.requireSubmission(subject, input);
            return reviews.findById(previous.getTargetRequestId()).orElseThrow();
        }
        p.requireVersion(input.contentVersion());
        ProjectReviewRequest active = reviews.findFirstByProjectIdAndStatusOrderByIdAsc(id, "REQUESTED").orElse(null);
        if (active != null) {
            if (active.getContentVersion() != p.getContentVersion()
                    || commands.existsByProjectIdAndKindAndTargetRequestIdAndState(
                            id, ProjectRequestKind.WITHDRAW_REVIEW, active.getId(), "PENDING"))
                throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 심사가 있습니다. 철회 결과를 확인해 주세요.");
            return accepted(p, input, active);
        }
        ProjectReviewRequest current = readProjects.reviewRequest(p);
        if (current != null && "APPROVED".equals(current.getStatus())) return accepted(p, input, current);
        p.requireReady();
        Build b =
                builds.findById(p.getCurrentBuildId())
                        .filter(item -> item.getProjectId().equals(id))
                        .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        if (storage.size(b.getStoragePath()) != b.getFileSize())
            throw new BusinessException(ErrorCode.CONFLICT, "빌드 파일을 확인할 수 없습니다.");
        ProjectSnapshot snapshot = snapshotFrom(p, b);
        ProjectReviewRequest review =
                reviews.saveAndFlush(
                        ProjectReviewRequest.create(
                                id, p.getContentVersion(), b.getId(), json(snapshot)));
        p.linkReviewRequest(review.getId());
        ProjectReviewRequestedEvent event = ProjectReviewRequestedEvent.of(review.getId(), snapshot);
        review.requested(event.eventId());
        outbox.record(
                "Project",
                p.getProductCode(),
                event);
        return accepted(p, input, review);
    }

    private ProjectReviewRequest accepted(Project project, SubmissionCommand input, ProjectReviewRequest review) {
        commands.save(ProjectRequestCommand.submission(project, ProjectRequestKind.REQUEST_REVIEW, input, review.getId()));
        return review;
    }

    private static ProjectSnapshot snapshotFrom(Project project, Build build) {
        return new ProjectSnapshot(
                project.getId(),
                project.getProductCode(),
                project.getOwnerSubject(),
                project.getContentVersion(),
                project.getTitle(),
                project.getShortDescription(),
                project.getThumbnailUrl(),
                project.getVideoUrl(),
                project.getContent(),
                project.getPrice(),
                "KRW",
                build.getId(),
                build.getVersion(),
                build.getFileSize(),
                build.getChecksum(),
                build.getStoragePath());
    }

    private String json(ProjectSnapshot value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
