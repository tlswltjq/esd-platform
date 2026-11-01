package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.settlement.config.PortOneSettlementProperties;
import com.stove.settlement.config.SettlementHttpProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementProviderConfigurationTest {
    @Test void portOneSelectionDoesNotRequireMockCredentialsOrFallBackToMock() {
        URI catalog = URI.create("http://catalog:8081"); Duration timeout = Duration.ofSeconds(2);
        assertThatCode(() -> new SettlementHttpProperties(null, null, catalog, "catalog-key", timeout, timeout, "PORTONE_TEST"))
                .doesNotThrowAnyException();
        for (String provider : List.of("SIMULATED", "PORTONE_LIVE", "UNKNOWN"))
            assertThatThrownBy(() -> new SettlementHttpProperties(null, null, catalog, "catalog-key", timeout, timeout, provider))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void testLookupConfigurationRejectsCredentialDestinationsAndRedactsSecrets() {
        for (String endpoint : List.of("https://other.example", "http://api.portone.io", "https://api.portone.io?secret=x",
                "https://user@api.portone.io", "https://api.portone.io/wrong-path")) {
            PortOneSettlementProperties properties = properties(endpoint);
            assertThatThrownBy(properties::requireConfigured).isInstanceOf(IllegalArgumentException.class);
        }
        PortOneSettlementProperties properties = properties("https://api.portone.io");
        assertThatCode(properties::requireConfigured).doesNotThrowAnyException();
        assertThat(properties.toString()).doesNotContain("read-secret");
    }
    private PortOneSettlementProperties properties(String endpoint) {
        return new PortOneSettlementProperties(URI.create(endpoint), "read-secret", "store-test", "channel-test",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
