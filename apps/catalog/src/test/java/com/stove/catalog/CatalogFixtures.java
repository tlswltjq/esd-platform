package com.stove.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.*;

import java.util.UUID;

public final class CatalogFixtures {
    public static long id() {
        return UUID.randomUUID().getMostSignificantBits() >>> 1;
    }

    public static ProjectReleaseRequestedEvent event(long project, long version) {
        return ProjectReleaseRequestedEvent.of(
                id(),
                id(),
                new ProjectSnapshot(
                        project,
                        "game-" + project,
                        "creator",
                        version,
                        "Title " + version,
                        "Summary",
                        "https://example.com/thumb.png",
                        null,
                        "Content",
                        1000,
                        "KRW",
                        version,
                        "build-" + version,
                        10,
                        "a".repeat(64),
                        "s3://stove-builds/file.zip"));
    }

    public static String json(Object value) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static EventEnvelope envelope(ProjectReleaseRequestedEvent e) {
        return new EventEnvelope(e.eventId(), e.eventType(), e.partitionKey(), json(e));
    }
}
