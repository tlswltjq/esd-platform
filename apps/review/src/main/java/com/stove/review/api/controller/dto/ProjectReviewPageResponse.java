package com.stove.review.api.controller.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record ProjectReviewPageResponse(
        List<ProjectReviewSummary> items, int page, int size, long totalElements, int totalPages) {
    public static ProjectReviewPageResponse from(Page<ProjectReviewSummary> page) {
        return new ProjectReviewPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
