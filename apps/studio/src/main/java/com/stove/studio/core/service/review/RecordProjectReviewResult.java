package com.stove.studio.core.service.review;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.*;
import com.stove.studio.core.domain.review.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records an external decision on its request; it never judges or changes project metadata. */
@Service
@RequiredArgsConstructor
@Transactional
public class RecordProjectReviewResult {
    public static final String CONSUMER_GROUP = "studio-project";
    private final ProjectReviewRequestRepository requests;
    private final ProcessedEventGuard guard;

    public void execute(ProjectReviewResult result) {
        if (!guard.firstDelivery(result.eventId(), CONSUMER_GROUP, result.eventType())) return;
        ProjectReviewRequest request =
                requests.lock(result.reviewRequestId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.getProjectId().equals(result.projectId())
                || request.getContentVersion() != result.contentVersion())
            throw new BusinessException(ErrorCode.CONFLICT, "심사 요청이 일치하지 않습니다.");
        request.recordResult(result.approved(), result.feedback(), result.actor());
    }
}
