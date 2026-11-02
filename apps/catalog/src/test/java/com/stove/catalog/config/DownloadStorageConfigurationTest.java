package com.stove.catalog.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DownloadStorageConfigurationTest {
    @Test void disabledStorageCannotAcceptCommandsAndDoesNotInventCredentials() {
        DownloadStorageProperties properties = new DownloadStorageProperties(false, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> new DownloadStorageConfiguration().buildDistribution(properties).requireConfigured())
                .isInstanceOf(BusinessException.class);
    }
    @Test void enabledStorageRequiresBothExplicitAccountsAndFiniteTimeouts() {
        assertThatThrownBy(() -> new DownloadStorageProperties(true, null, null, "builds", "tagger", "secret", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DownloadStorageProperties(true, null, null, "builds", "same", "secret", "same", "secret", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DownloadStorageProperties(true, "http://user:secret@host", null, "builds", "tagger", "secret", "reader", "secret", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DownloadStorageProperties(true, null, null, "builds", "tagger", "secret", "reader", "secret", Duration.ofSeconds(11)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
