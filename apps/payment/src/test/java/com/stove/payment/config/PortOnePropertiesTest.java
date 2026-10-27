package com.stove.payment.config;

import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.SECRET;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortOnePropertiesTest {
    @Test void publicIpHttpsEndpointIsAcceptedWithoutExposingServerSecrets() {
        PortOneProperties properties = settings(URI.create("https://192.0.2.10/api/v1/payments/webhooks/portone"));
        assertThatCode(properties::requireConfigured).doesNotThrowAnyException();
        assertThat(properties.toString()).doesNotContain("test-api-secret", SECRET);
    }

    @Test void anActiveProviderRequiresAnExplicitPublicHttpsWebhookUrl() {
        assertThatThrownBy(() -> settings(null).requireConfigured()).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : List.of("http://192.0.2.10/webhook", "/relative", "https://user:password@example.com/webhook",
                "https://example.com/webhook?secret=bad", "https://example.com/webhook#fragment")) {
            assertThatThrownBy(() -> settings(URI.create(invalid)).requireConfigured()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private PortOneProperties settings(URI webhook) {
        return new PortOneProperties(URI.create("https://api.portone.io"), "test-api-secret", STORE, CHANNEL,
                SECRET, Duration.ofSeconds(2), Duration.ofSeconds(65), webhook);
    }
}
