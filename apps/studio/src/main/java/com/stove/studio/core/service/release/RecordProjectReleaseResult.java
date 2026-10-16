package com.stove.studio.core.service.release;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.*;
import com.stove.studio.core.domain.release.*;
import com.stove.studio.core.service.review.RecordProjectReviewResult;

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

    public void execute(ProjectReleaseResult result) {
        if (!guard.firstDelivery(result.eventId(), RecordProjectReviewResult.CONSUMER_GROUP, result.eventType()))
            return;
        ProjectReleaseRequest request =
                requests.lock(result.releaseRequestId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.getProjectId().equals(result.projectId())
                || request.getContentVersion() != result.contentVersion())
            throw new BusinessException(ErrorCode.CONFLICT, "출시 요청이 일치하지 않습니다.");
        if (!java.util.Objects.equals(request.getRequestEventId(), result.requestEventId())) return;
        request.recordResult(result.success(), result.reason());
    }
}
