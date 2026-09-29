package com.stove.common.event.payload;

import java.time.Instant;

/** Published pricing schedule; intervals are [startsAt, endsAt). */
public record PromotionWindow(Long id, long discountPerUnit, String bearer,
                              Instant startsAt, Instant endsAt) {
    public boolean activeAt(Instant instant) {
        return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
    }
}
