package com.stove.studio.core.service.release;

import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.domain.project.ProjectRequestCommand;
import com.stove.studio.core.domain.project.ProjectRequestCommandRepository;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.domain.project.SubmissionCommand;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseRequestRepository;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.project.ReadProjects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates the creator's current review result and emits a release request. */
@Service
@RequiredArgsConstructor
@Transactional
public class RequestProjectRelease {
    private final ProjectRepository projects;
    private final ProjectReleaseRequestRepository requests;
    private final ReadProjects readProjects;
    private final OutboxRecorder outbox;
    private final BuildStorage storage;
    private final ProjectRequestCommandRepository commands;

    public ProjectReleaseRequest execute(Long id, String subject, SubmissionCommand input) {
        Project p = projects.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        p.requireOwner(subject);
        ProjectRequestCommand previous = commands.findByProjectIdAndKindAndRequestKey(
                id, ProjectRequestKind.REQUEST_RELEASE, input.requestId()).orElse(null);
        if (previous != null) {
            previous.requireSubmission(subject, input);
            return requests.findById(previous.getTargetRequestId()).orElseThrow();
        }
        p.requireVersion(input.contentVersion());
        ProjectReviewRequest review = readProjects.reviewRequest(p);
        if (review == null || !review.approvedFor(p.getContentVersion()))
            throw new BusinessException(ErrorCode.CONFLICT, "현재 상세정보 버전의 심사 승인 결과가 필요합니다.");
        ProjectReleaseRequest active = requests.findFirstByProjectIdAndStatusOrderByIdAsc(id, "REQUESTED").orElse(null);
        if (active != null) {
            if (active.getContentVersion() != p.getContentVersion()
                    || commands.existsByProjectIdAndKindAndTargetRequestIdAndState(
                            id, ProjectRequestKind.WITHDRAW_RELEASE, active.getId(), "PENDING"))
                throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 출시가 있습니다. 최종 결과 또는 철회 완료를 확인해 주세요.");
            return accepted(p, input, active);
        }
        ProjectReleaseRequest current = readProjects.releaseRequest(p);
        if (current != null && "COMPLETED".equals(current.getStatus())) return accepted(p, input, current);
        ProjectSnapshot snapshot = readProjects.snapshot(review);
        if (storage.size(snapshot.storagePath()) != snapshot.fileSize())
            throw new BusinessException(ErrorCode.CONFLICT, "빌드 파일을 확인할 수 없습니다.");
        ProjectReleaseRequest request =
                requests.saveAndFlush(
                        ProjectReleaseRequest.create(id, review.getId(), p.getContentVersion()));
        ProjectReleaseRequestedEvent event = ProjectReleaseRequestedEvent.of(request.getId(), review.getId(), snapshot);
        request.requested(event.eventId());
        p.linkReleaseRequest(request.getId());
        outbox.record("Project", p.getProductCode(), event);
        return accepted(p, input, request);
    }

    private ProjectReleaseRequest accepted(Project project, SubmissionCommand input, ProjectReleaseRequest request) {
        commands.save(ProjectRequestCommand.submission(project, ProjectRequestKind.REQUEST_RELEASE, input, request.getId()));
        return request;
    }
}
