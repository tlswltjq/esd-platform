package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.ApprovalInvestigationView;
import java.time.Instant;

public record ApprovalInvestigationResponse(String requestId, String approvalId, String operatorSubject, String note,
        Instant recordedAt, Instant reviewStartedAt) {
    public static ApprovalInvestigationResponse from(ApprovalInvestigationView view) {
        return new ApprovalInvestigationResponse(view.requestId(), view.approvalId(), view.operatorSubject(), view.note(), view.recordedAt(), view.reviewStartedAt());
    }
}
