package com.stove.common.kafka.contract.payload;

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
        String storagePath) {}
