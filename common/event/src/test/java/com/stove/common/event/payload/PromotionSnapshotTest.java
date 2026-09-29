package com.stove.common.event.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PromotionSnapshotTest {
    @Test
    void oldOrderLineJsonStillDeserializesAtItsOriginalPrice() throws Exception {
        OrderLine line = new ObjectMapper().readValue(
                "{\"productId\":1,\"productName\":\"game\",\"sellerId\":2,\"unitPrice\":10000,\"quantity\":1}",
                OrderLine.class);
        assertThat(line.listUnitPrice()).isEqualTo(10_000);
        assertThat(line.discountAmount()).isZero();
        assertThat(line.lineAmount()).isEqualTo(10_000);
    }

    @Test
    void promotionUsesInclusiveStartAndExclusiveEnd() {
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        Instant end = start.plusSeconds(60);
        PromotionWindow window = new PromotionWindow(1L, 2_000, "SELLER", start, end);
        assertThat(window.activeAt(start.minusNanos(1))).isFalse();
        assertThat(window.activeAt(start)).isTrue();
        assertThat(window.activeAt(end.minusNanos(1))).isTrue();
        assertThat(window.activeAt(end)).isFalse();
    }
}
