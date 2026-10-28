package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundInvestigationView;
import java.time.Instant;

public record RefundInvestigationResponse(String requestId, String attemptId, String operatorSubject, String note,
        Instant recordedAt, Instant delayedSince) {
    public static RefundInvestigationResponse from(RefundInvestigationView view) {
        return new RefundInvestigationResponse(view.requestId(), view.attemptId(), view.operatorSubject(), view.note(), view.recordedAt(), view.delayedSince());
    }
}
