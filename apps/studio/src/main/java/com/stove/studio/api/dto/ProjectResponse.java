package com.stove.studio.api.dto;

import com.stove.studio.domain.GameProject;
import com.stove.studio.domain.ProjectStatus;

public record ProjectResponse(
        Long gameId,
        String productCode,
        String title,
        Long sellerId,
        long price,
        String currency,
        boolean selfRated,
        ProjectStatus status,
        String ratingCode,
        String rejectReason
) {
    public static ProjectResponse from(GameProject project) {
        return new ProjectResponse(
                project.getId(),
                project.getProductCode(),
                project.getTitle(),
                project.getSellerId(),
                project.getPrice(),
                project.getCurrency(),
                project.isSelfRated(),
                project.getStatus(),
                project.getRatingCode(),
                project.getRejectReason());
    }
}
