package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.*;

import com.stove.catalog.CatalogFixtures;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class CatalogProductTest {
    @Test
    void publicationPreservesIdentityAndFirstPublicationTime() {
        var s = CatalogFixtures.event(1, 1).snapshot();
        var first = Instant.parse("2026-10-15T00:00:00Z");
        var p = CatalogProduct.publish(s, 10L, first);
        p.requireIdentity(CatalogFixtures.event(1, 2).snapshot());
        p.replace(20L, first.plusSeconds(10));
        assertThat(p.getPublishedAt()).isEqualTo(first);
        assertThat(p.getUpdatedAt()).isEqualTo(first.plusSeconds(10));
        assertThat(p.getCurrentReleaseRequestId()).isEqualTo(20L);
        assertThatThrownBy(() -> p.requireIdentity(CatalogFixtures.event(2, 1).snapshot()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
