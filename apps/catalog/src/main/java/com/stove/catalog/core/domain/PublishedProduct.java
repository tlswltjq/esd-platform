package com.stove.catalog.core.domain;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;

import java.time.Instant;

public record PublishedProduct(
        Long productId, ProjectSnapshot snapshot, Instant publishedAt, Instant updatedAt) {}
