package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundDelayView;
import java.util.List;
import org.springframework.data.domain.Page;

public record RefundDelayPage(List<RefundDelayResponse> content, int page, int size, long totalElements, int totalPages) {
    public static RefundDelayPage from(Page<RefundDelayView> page) {
        return new RefundDelayPage(page.getContent().stream().map(RefundDelayResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
