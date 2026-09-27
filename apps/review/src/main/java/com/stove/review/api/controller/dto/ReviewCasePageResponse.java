package com.stove.review.api.controller.dto;

import com.stove.review.core.domain.ReviewCase;
import java.util.List;
import org.springframework.data.domain.Page;

public record ReviewCasePageResponse(List<ReviewCaseResponse> items, int page, int size,
                                     long totalElements, int totalPages) {
    public static ReviewCasePageResponse from(Page<ReviewCase> result) {
        return new ReviewCasePageResponse(result.getContent().stream().map(ReviewCaseResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
