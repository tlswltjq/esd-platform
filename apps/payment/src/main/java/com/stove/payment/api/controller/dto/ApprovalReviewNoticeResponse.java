package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.ApprovalReviewNotice;
import java.time.Instant;
import java.util.Optional;

public record ApprovalReviewNoticeResponse(ApprovalReviewNotice.Code code, String message, Instant since) {
    public static ApprovalReviewNoticeResponse from(Optional<ApprovalReviewNotice> notice) {
        return notice.map(value -> new ApprovalReviewNoticeResponse(value.code(), value.message(), value.since())).orElse(null);
    }
}
