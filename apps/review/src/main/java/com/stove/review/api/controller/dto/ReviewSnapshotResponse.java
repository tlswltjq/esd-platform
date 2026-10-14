package com.stove.review.api.controller.dto;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;

/** Reviewer-facing submission; internal object paths remain server-side. */
public record ReviewSnapshotResponse(
        Long projectId,
        String productCode,
        String ownerSubject,
        long contentVersion,
        String title,
        String shortDescription,
        String thumbnailUrl,
        String videoUrl,
        String content,
        long price,
        String currency,
        Long buildId,
        String version,
        long fileSize,
        String checksum) {
    public static ReviewSnapshotResponse from(ProjectSnapshot s) {
        return new ReviewSnapshotResponse(
                s.projectId(),
                s.productCode(),
                s.ownerSubject(),
                s.contentVersion(),
                s.title(),
                s.shortDescription(),
                s.thumbnailUrl(),
                s.videoUrl(),
                s.content(),
                s.price(),
                s.currency(),
                s.buildId(),
                s.version(),
                s.fileSize(),
                s.checksum());
    }
}
