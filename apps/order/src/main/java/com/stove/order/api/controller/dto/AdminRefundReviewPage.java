package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewRoundView;
import java.util.List;
import org.springframework.data.domain.Page;

public record AdminRefundReviewPage(List<AdminRefundReviewResponse> content, int page, int size, long totalElements, int totalPages) {
    public static AdminRefundReviewPage from(Page<RefundReviewRoundView> page) {
        return new AdminRefundReviewPage(page.getContent().stream().map(AdminRefundReviewResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
