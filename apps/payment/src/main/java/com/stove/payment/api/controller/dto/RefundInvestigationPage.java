package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundInvestigationView;
import java.util.List;
import org.springframework.data.domain.Page;

public record RefundInvestigationPage(List<RefundInvestigationResponse> content, int page, int size, long totalElements, int totalPages) {
    public static RefundInvestigationPage from(Page<RefundInvestigationView> page) {
        return new RefundInvestigationPage(page.getContent().stream().map(RefundInvestigationResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
