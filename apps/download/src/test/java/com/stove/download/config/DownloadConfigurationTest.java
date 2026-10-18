package com.stove.download.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DownloadConfigurationTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "file:///etc/passwd", "http://user:pass@host", "http://host/path", "http://host?x=1", "http://host#fragment"})
    void requiresFixedHttpBaseUrls(String url) {
        assertThatThrownBy(() -> new DownloadClientProperties(url, "http://catalog", "key", null, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresExplicitServiceKeyAndPositiveTimeouts() {
        assertThatThrownBy(() -> new DownloadClientProperties("http://license", "http://catalog", "", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DownloadClientProperties("http://license", "http://catalog", "key", Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresExplicitStorageCredentialsAndBoundedTtl() {
        assertThatThrownBy(() -> new ObjectStorageProperties(null, null, null, "", "", "bucket", null, null)).isInstanceOf(IllegalArgumentException.class);
        for (Duration ttl : new Duration[]{Duration.ZERO, Duration.ofMillis(999), Duration.ofMinutes(16)})
            assertThatThrownBy(() -> new ObjectStorageProperties(null, null, null, "reader", "secret", "bucket", ttl, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ObjectStorageProperties(null, null, null, "reader", "secret", "bucket", null, null).presignTtl()).isEqualTo(Duration.ofMinutes(5));
    }
}
