package com.stove.studio.core.service.project;

import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.domain.project.ProjectRequestCommand;
import com.stove.studio.core.domain.project.ProjectRequestCommandRepository;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.domain.project.WithdrawalResolution;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseRequestRepository;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequestRepository;
import com.stove.studio.core.service.review.RecordProjectReviewResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RecordProjectWithdrawalResult {
    public static final String CONSUMER_GROUP = RecordProjectReviewResult.CONSUMER_GROUP;
    private final ProjectRepository projects;
    private final ProjectRequestCommandRepository commands;
    private final ProjectReviewRequestRepository reviews;
    private final ProjectReleaseRequestRepository releases;
    private final ProcessedEventGuard guard;

    public void execute(WithdrawalResolution input) {
        ProjectWithdrawalResult result = input.result();
        Project project = projects.lock(result.projectId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ProjectRequestCommand command = commands.findById(result.withdrawalId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!project.getProductCode().equals(result.productCode()) || command.getKind() != input.kind())
            throw new BusinessException(ErrorCode.CONFLICT, "철회 결과의 종류·상품이 다릅니다.");
        boolean pending = "PENDING".equals(command.getState());
        command.resolve(result);
        if (!guard.firstDelivery(input.eventId(), CONSUMER_GROUP, input.eventType()) && pending)
            throw new BusinessException(ErrorCode.CONFLICT, "철회 결과 이벤트가 다른 명령에 사용됐습니다.");
        if (input.kind() == ProjectRequestKind.WITHDRAW_REVIEW) {
            ProjectReviewRequest request = reviews.lock(result.requestId()).orElseThrow();
            requireTarget(project, request.getProjectId(), request.getContentVersion(), request.getRequestEventId(), result);
            request.recordResolution(result.status(), result.actor(), result.feedback(), result.finalizedAt());
        } else {
            ProjectReleaseRequest request = releases.lock(result.requestId()).orElseThrow();
            requireTarget(project, request.getProjectId(), request.getContentVersion(), request.getRequestEventId(), result);
            request.recordResolution(result.status(), result.actor(), result.feedback(), result.finalizedAt());
        }
    }

    private void requireTarget(Project project, Long projectId, long version, String originalEventId,
            ProjectWithdrawalResult result) {
        if (!project.getId().equals(projectId) || version != result.contentVersion()
                || (originalEventId != null && !originalEventId.equals(result.requestEventId())))
            throw new BusinessException(ErrorCode.CONFLICT, "철회 결과의 원요청이 일치하지 않습니다.");
    }
}
