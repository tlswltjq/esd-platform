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

    public void execute(
            String eventId,
            String eventType,
            Long reviewRequestId,
            Long projectId,
            long version,
            boolean approved,
            String feedback,
            String actor) {
        if (!guard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) return;
        var request =
                requests.lock(reviewRequestId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!request.getProjectId().equals(projectId) || request.getContentVersion() != version)
            throw new BusinessException(ErrorCode.CONFLICT, "심사 요청이 일치하지 않습니다.");
        request.recordResult(approved, feedback, actor);
    }
}
