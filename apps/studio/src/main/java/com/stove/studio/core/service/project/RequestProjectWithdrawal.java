package com.stove.studio.core.service.project;

import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.domain.project.ProjectRequestCommand;
import com.stove.studio.core.domain.project.ProjectRequestCommandRepository;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.domain.project.WithdrawalCommand;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseRequestRepository;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequestRepository;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RequestProjectWithdrawal {
    private final ProjectRepository projects;
    private final ProjectReviewRequestRepository reviews;
    private final ProjectReleaseRequestRepository releases;
    private final ProjectRequestCommandRepository commands;
    private final OutboxRecorder outbox;

    public ProjectRequestCommand review(Long projectId, Long requestId, String subject, WithdrawalCommand input) {
        return execute(projectId, requestId, subject, input, ProjectRequestKind.WITHDRAW_REVIEW);
    }

    public ProjectRequestCommand release(Long projectId, Long requestId, String subject, WithdrawalCommand input) {
        return execute(projectId, requestId, subject, input, ProjectRequestKind.WITHDRAW_RELEASE);
    }

    private ProjectRequestCommand execute(Long projectId, Long requestId, String subject,
            WithdrawalCommand input, ProjectRequestKind kind) {
        Project project = projects.lock(projectId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        project.requireOwner(subject);
        ProjectRequestCommand previous = commands.findByProjectIdAndKindAndRequestKey(
                projectId, kind, input.requestId()).orElse(null);
        if (previous != null) {
            previous.requireWithdrawal(subject, requestId, input);
            return previous;
        }
        if (commands.existsByProjectIdAndKindAndTargetRequestIdAndState(projectId, kind, requestId, "PENDING"))
            throw new BusinessException(ErrorCode.CONFLICT, "같은 요청의 철회가 처리 중입니다.");
        long version;
        if (kind == ProjectRequestKind.WITHDRAW_REVIEW) {
            ProjectReviewRequest target = reviews.lock(requestId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            requirePending(projectId, target.getProjectId(), target.getStatus());
            version = target.getContentVersion();
        } else {
            ProjectReleaseRequest target = releases.lock(requestId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            requirePending(projectId, target.getProjectId(), target.getStatus());
            version = target.getContentVersion();
        }
        ProjectRequestCommand command = commands.saveAndFlush(
                ProjectRequestCommand.withdrawal(project, kind, input, requestId, version));
        if (kind == ProjectRequestKind.WITHDRAW_REVIEW) {
            outbox.record("Project", project.getProductCode(),
                    ProjectReviewWithdrawalRequestedEvent.of(command.instruction(project.getProductCode())));
        } else {
            outbox.record("Project", project.getProductCode(),
                    ProjectReleaseWithdrawalRequestedEvent.of(command.instruction(project.getProductCode())));
        }
        return command;
    }

    private void requirePending(Long expectedProject, Long projectId, String status) {
        if (!expectedProject.equals(projectId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!"REQUESTED".equals(status))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 확정된 요청은 철회할 수 없습니다.");
    }

    @Transactional(readOnly = true)
    public ProjectRequestCommand receipt(Long projectId, Long requestId, Long withdrawalId,
            String subject, ProjectRequestKind kind) {
        Project project = projects.findById(projectId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        project.requireOwner(subject);
        ProjectRequestCommand command = commands.findById(withdrawalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!projectId.equals(command.getProjectId()) || !requestId.equals(command.getTargetRequestId())
                || kind != command.getKind()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return command;
    }

    @Transactional(readOnly = true)
    public List<ProjectRequestCommand> receipts(Long projectId, Long requestId, String subject,
            ProjectRequestKind kind, int page, int size) {
        Project project = projects.findById(projectId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        project.requireOwner(subject);
        Long targetProject = kind == ProjectRequestKind.WITHDRAW_REVIEW
                ? reviews.findById(requestId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).getProjectId()
                : releases.findById(requestId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).getProjectId();
        if (!projectId.equals(targetProject)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return commands.findByProjectIdAndKindAndTargetRequestIdOrderByIdDesc(projectId, kind, requestId,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size))));
    }
}
