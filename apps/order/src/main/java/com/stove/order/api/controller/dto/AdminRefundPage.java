package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewView;
import java.util.List;
import org.springframework.data.domain.Page;

public record AdminRefundPage(List<AdminRefundResponse> content, int page, int size, long totalElements, int totalPages) {
    public static AdminRefundPage from(Page<RefundReviewView> page) {
        return new AdminRefundPage(page.map(AdminRefundResponse::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
