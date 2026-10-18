package com.stove.store;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import java.time.Instant;
import java.util.UUID;

public final class StoreFixtures {
    private StoreFixtures() {}
    public static long id() { return UUID.randomUUID().getMostSignificantBits() >>> 1; }
    public static CatalogProductSnapshot product(long id, long version, String title, long price) {
        return new CatalogProductSnapshot(id, "game-" + id, title, "Summary", "https://example.com/thumb.png",
                price, "KRW", Instant.parse("2026-01-01T00:00:00.123456Z"), "Content " + version,
                null, version, "build-" + version, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(version));
    }
}
