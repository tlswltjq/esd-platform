package com.stove.common.kafka.contract.payload;

import java.util.Comparator;
import java.util.List;

/** One immutable store page and full build submitted for review. */
public record ProjectSnapshot(
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
        String checksum,
        String storagePath,
        List<String> genres) {
    public ProjectSnapshot {
        if (genres != null) genres = genres.stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList();
    }
}
