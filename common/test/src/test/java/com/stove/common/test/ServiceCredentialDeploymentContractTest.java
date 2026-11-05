package com.stove.common.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ServiceCredentialDeploymentContractTest {
    private static final Map<String, Set<String>> RECIPIENTS = Map.ofEntries(
            Map.entry("CATALOG_STORE_SERVICE_KEY", Set.of("catalog", "store")),
            Map.entry("CATALOG_ORDER_SERVICE_KEY", Set.of("catalog", "order")),
            Map.entry("CATALOG_DOWNLOAD_SERVICE_KEY", Set.of("catalog", "download")),
            Map.entry("CATALOG_SETTLEMENT_SERVICE_KEY", Set.of("catalog", "settlement")),
            Map.entry("PAYMENT_ORDER_SERVICE_KEY", Set.of("payment", "order")),
            Map.entry("PAYMENT_APPROVAL_PAYLOAD_KEY", Set.of("payment")),
            Map.entry("BFF_CLIENT_SECRET", Set.of("auth", "gateway")),
            Map.entry("MOCK_PG_PAYMENT_KEY", Set.of("mock-pg", "payment")),
            Map.entry("MOCK_PG_SETTLEMENT_KEY", Set.of("mock-pg", "settlement")),
            Map.entry("MOCK_PG_CONTROL_KEY", Set.of("mock-pg")),
            Map.entry("DOWNLOAD_STORAGE_SECRET_KEY", Set.of("catalog", "download")));

    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("settings.gradle"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Repository root not found");
        return path;
    }

    private static Map<?, ?> services(String file) throws IOException {
        try (InputStream input = Files.newInputStream(root().resolve(file))) {
            Object document = new Yaml().load(input);
            assertThat(document).isInstanceOf(Map.class);
            Object services = ((Map<?, ?>) document).get("services");
            assertThat(services).isInstanceOf(Map.class);
            return (Map<?, ?>) services;
        }
    }

    @Test
    void baseComposeDistributesEachCredentialOnlyToDeclaredRecipients() throws IOException {
        Map<?, ?> services = services("docker-compose.apps.yml");
        assertThat(services).hasSizeGreaterThanOrEqualTo(12);
        for (Map.Entry<String, Set<String>> credential : RECIPIENTS.entrySet()) {
            Set<String> actual = new LinkedHashSet<>();
            for (Map.Entry<?, ?> service : services.entrySet()) {
                Map<?, ?> definition = (Map<?, ?>) service.getValue();
                Object raw = definition.get("environment");
                if (raw instanceof Map<?, ?> environment && environment.containsKey(credential.getKey())) {
                    actual.add(service.getKey().toString());
                    String value = environment.get(credential.getKey()).toString();
                    assertThat(value).as("Credential must use its own external setting: %s", credential.getKey())
                            .startsWith("${" + credential.getKey() + ":-").endsWith("}");
                }
            }
            assertThat(actual).as("Recipients for %s", credential.getKey()).isEqualTo(credential.getValue());
        }
    }

    @Test
    void localOverrideDoesNotAddCredentialsToOtherServices() throws IOException {
        for (Map.Entry<?, ?> service : services("docker-compose.apps.local.yml").entrySet()) {
            Object raw = ((Map<?, ?>) service.getValue()).get("environment");
            if (!(raw instanceof Map<?, ?> environment)) continue;
            for (Object name : environment.keySet()) {
                assertThat(RECIPIENTS.containsKey(name.toString()))
                        .as("Local override must not duplicate credential settings").isFalse();
            }
        }
    }
}
