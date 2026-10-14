package com.stove.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.*;
import com.stove.review.core.domain.ProjectReview;

public final class ReviewFixtures {
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private ReviewFixtures() {}

    public static ProjectSnapshot snapshot(long project, String path, long size) {
        return snapshot(project, path, size, "a".repeat(64));
    }

    public static ProjectSnapshot snapshot(long project, String path, long size, String checksum) {
        return new ProjectSnapshot(
                project,
                "GAME-" + project,
                "creator",
                1,
                "Game",
                "Summary",
                "https://example.com/thumbnail.png",
                null,
                "Submitted content",
                1000,
                "KRW",
                1L,
                "1",
                size,
                checksum,
                path);
    }

    public static ProjectReviewRequestedEvent event(long id, long project) {
        return ProjectReviewRequestedEvent.of(
                id, snapshot(project, "s3://stove-builds/build.zip", 10));
    }

    public static EventEnvelope envelope(ProjectReviewRequestedEvent event) {
        return new EventEnvelope(
                event.eventId(), event.eventType(), event.partitionKey(), json(event));
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static ProjectReview review(long id) {
        var event = event(id, id);
        return ProjectReview.requested(event, json(event.snapshot()));
    }
}
