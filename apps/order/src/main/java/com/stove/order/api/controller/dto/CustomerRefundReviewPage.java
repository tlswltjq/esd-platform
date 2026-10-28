package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewRoundView;
import java.util.List;
import org.springframework.data.domain.Page;

public record CustomerRefundReviewPage(List<CustomerRefundReviewResponse> content, int page, int size, long totalElements, int totalPages) {
    public static CustomerRefundReviewPage from(Page<RefundReviewRoundView> page) {
        return new CustomerRefundReviewPage(page.getContent().stream().map(CustomerRefundReviewResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
