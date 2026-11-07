package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.ApprovalInvestigationView;
import com.stove.payment.core.domain.ApprovalReviewHistory;
import java.util.List;
import org.springframework.data.domain.Page;

public record ApprovalInvestigationPage(List<ApprovalInvestigationResponse> content, int page, int size, long totalElements, int totalPages,
        ApprovalClosureRecordResponse closure) {
    public static ApprovalInvestigationPage from(ApprovalReviewHistory history) {
        Page<ApprovalInvestigationView> page = history.investigations();
        return new ApprovalInvestigationPage(page.getContent().stream().map(ApprovalInvestigationResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), ApprovalClosureRecordResponse.from(history.closure()));
    }
}
