package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundCancellationHoldView;
import java.util.List;
import org.springframework.data.domain.Page;

public record RefundCancellationHoldPage(List<RefundCancellationHoldResponse> content, int page, int size, long totalElements, int totalPages) {
    public static RefundCancellationHoldPage from(Page<RefundCancellationHoldView> page) {
        return new RefundCancellationHoldPage(page.getContent().stream().map(RefundCancellationHoldResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
