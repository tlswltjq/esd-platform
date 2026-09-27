package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.ProjectStatus;
import com.stove.studio.core.domain.ProductKind;

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
        String rejectReason,
        ProductKind productKind,
        Long parentGameId,
        String editionName
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
                project.getRejectReason(),
                project.getProductKind(),
                project.getParentGameId(),
                project.getEditionName());
    }
}
