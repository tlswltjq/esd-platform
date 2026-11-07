package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.ApprovalReviewView;
import java.util.List;
import org.springframework.data.domain.Page;

public record ApprovalReviewPage(List<ApprovalReviewResponse> content, int page, int size, long totalElements, int totalPages) {
    public static ApprovalReviewPage from(Page<ApprovalReviewView> page) {
        return new ApprovalReviewPage(page.getContent().stream().map(ApprovalReviewResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
