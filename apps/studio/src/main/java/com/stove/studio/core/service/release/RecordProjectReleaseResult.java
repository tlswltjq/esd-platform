package com.stove.studio.core.service.release;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseRequestRepository;
import com.stove.studio.core.domain.release.ProjectReleaseResult;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.service.review.RecordProjectReviewResult;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Copies the external outcome to a release request; it does not publish a project or product. */
@Service
@RequiredArgsConstructor
@Transactional
public class RecordProjectReleaseResult {
    private final ProjectReleaseRequestRepository requests;
    private final ProcessedEventGuard guard;
    private final ProjectRepository projects;

    public void execute(ProjectReleaseResult result) {
        if (!guard.firstDelivery(result.eventId(), RecordProjectReviewResult.CONSUMER_GROUP, result.eventType()))
            return;
        projects.lock(result.projectId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ProjectReleaseRequest request =
                requests.lock(result.releaseRequestId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.getProjectId().equals(result.projectId())
                || request.getContentVersion() != result.contentVersion())
            throw new BusinessException(ErrorCode.CONFLICT, "출시 요청이 일치하지 않습니다.");
        if (!Objects.equals(request.getRequestEventId(), result.requestEventId())) return;
        request.recordResult(result.success(), result.reason());
    }
}
