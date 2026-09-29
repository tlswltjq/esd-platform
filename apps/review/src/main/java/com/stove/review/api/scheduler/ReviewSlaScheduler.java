package com.stove.review.api.scheduler;

import com.stove.review.core.service.SubmissionReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewSlaScheduler {
    private final SubmissionReviewService reviewService;

    @Scheduled(fixedDelayString = "${stove.review.sla-sweep-ms:60000}")
    public void expireOverdueCases() {
        reviewService.expireDue();
    }
}
