package com.stove.store;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.store.core.domain.StoreProductRepository;
import com.stove.store.core.port.CatalogProducts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false", "stove.store.sync.poll-interval-ms=20",
        "stove.store.sync.retry-delay=PT0.05S", "stove.store.sync.reconcile-interval=PT0.1S"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, StoreAutomaticRecoveryIntegrationTest.Source.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreAutomaticRecoveryIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ToggleCatalog catalog;
    @Autowired StoreProductRepository products;
    @Autowired MeterRegistry meters;

    @Test void sourceRecoveryAndSubsequentOutageNeedNoRestartOrManualInvocation() {
        assertThat(http.getForEntity("/api/v1/storefront/products", String.class).getStatusCode().value()).isEqualTo(503);
        catalog.available.set(true);
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(http.getForEntity("/api/v1/storefront/products/game-701", String.class).getStatusCode().value()).isEqualTo(200);
            assertThat(products.findById(701L)).isPresent();
        });
        int before = catalog.calls.get();
        catalog.available.set(false);
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(catalog.calls.get()).isGreaterThan(before));
        assertThat(http.getForEntity("/api/v1/storefront/products/game-701", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(meters.get("stove.store.sync.ready").gauge().value()).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Source {
        @Bean @Primary ToggleCatalog toggleCatalog() { return new ToggleCatalog(); }
    }

    static class ToggleCatalog implements CatalogProducts {
        final AtomicBoolean available = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        public List<String> codes(int page) {
            calls.incrementAndGet();
            if (!available.get()) throw new IllegalStateException("source unavailable");
            return page == 0 ? List.of("game-701") : List.of();
        }
        public CatalogProductSnapshot detail(String code) { return product(701, 1, "Recovered automatically", 1000); }
    }
}
