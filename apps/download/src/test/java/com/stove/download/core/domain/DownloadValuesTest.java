package com.stove.download.core.domain;

import static com.stove.download.DownloadFixtures.build;
import static org.assertj.core.api.Assertions.*;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class DownloadValuesTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "../game", "a/b", "a?b", "한글", "-game"})
    void rejectsInvalidProductCode(String code) {
        assertThatThrownBy(() -> new DownloadRequest("buyer", code)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new OwnedProduct(1, code)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" "})
    void requiresAnAuthenticatedSubject(String subject) {
        assertThatThrownBy(() -> new DownloadRequest(subject, "game")).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsInvalidBuildValuesAtConstruction() {
        PublishedBuild valid = build();
        assertThatThrownBy(() -> new PublishedBuild(0, valid.productCode(), 1, 1, 1, "v", 1, valid.checksum(), valid.storagePath()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublishedBuild(1, "game", 1, 1, 1, "v", 1, "short", valid.storagePath()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublishedBuild(1, "game", 1, 1, 1, "v", 0, valid.checksum(), valid.storagePath()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
