package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class PublishedBuildTest {
    @Test void refusesIncompleteStoredBuildIdentityAndFileValues() {
        assertThatThrownBy(() -> new PublishedBuild(0L, "game", 1L, 1, 1L, "v1", 10, "a".repeat(64), "s3://bucket/key"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PublishedBuild(1L, "game", 1L, 1, 1L, "v1", 0, "a".repeat(64), "s3://bucket/key"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PublishedBuild(1L, "game", 1L, 1, 1L, "v1", 10, "short", "s3://bucket/key"))
                .isInstanceOf(BusinessException.class);
    }
}
