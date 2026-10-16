package com.stove.store;

import com.stove.store.api.listener.CatalogProductMapper;
import static com.stove.store.StoreFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.*;
import com.stove.common.kafka.contract.*;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.web.error.BusinessException;
import com.stove.store.core.domain.*;
import com.stove.store.core.port.CatalogProducts;
import com.stove.store.core.service.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.store.sync.enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class StoreIntegrationTest {
    @Autowired ApplyCatalogProduct apply;
    @Autowired SynchronizeCatalogProducts sync;
    @Autowired SearchStoreProducts search;
    @Autowired ReadStoreProduct read;
    @Autowired StoreProductRepository products;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean CatalogProducts catalog;
    @LocalServerPort int port;

    @BeforeEach void ready() {
        products.deleteAll();
        when(catalog.codes(anyInt())).thenReturn(List.of());
        sync.execute();
        clearInvocations(catalog);
    }
    private RestClient http() { return RestClient.create("http://localhost:" + port); }
    private int status(String path) {
        return http().get().uri(path).exchange((request, response) -> response.getStatusCode().value());
    }
    private ProducerRecord<String, String> record(CatalogProductPublishedEvent event, String key) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<String, String>(Topics.CATALOG, key, mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }
    @Test void newerVersionWinsAndSameVersionConflictsAreRolledBack() {
        CatalogProductSnapshot old = product(1, 1, "Old", 1000);
        CatalogProductSnapshot latest = product(1, 2, "Latest", 2000);
        apply.execute(latest); apply.execute(old); apply.execute(latest);
        assertThatThrownBy(() -> apply.execute(product(1, 2, "Conflict", 1)))
                .isInstanceOf(BusinessException.class);
        assertThat(read.execute(old.productCode())).isEqualTo(latest);
        assertThat(products.count()).isEqualTo(1);
    }
    @Test void concurrentHttpAndEventInsertsConvergeToHighestVersion() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            ArrayList<Future<?>> futures = new ArrayList<Future<?>>();
            for (long version : new long[]{1, 3, 2}) futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                apply.execute(product(1, version, "Version " + version, version * 1000));
            }));
            start.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
            assertThat(read.execute("game-1").contentVersion()).isEqualTo(3);
            assertThat(products.count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void invalidEnvelopeAndIdentityConflictDoNotChangeProjection() {
        CatalogProductSnapshot p = product(1, 1, "Title", 1000);
        CatalogProductPublishedEvent event = CatalogProductPublishedEvent.of(p);
        assertThatThrownBy(() -> apply.execute(CatalogProductMapper.from(new EventEnvelope(event.eventId(), event.eventType(), "wrong", ""), event)))
                .isInstanceOf(BusinessException.class);
        assertThat(products.count()).isZero();
        apply.execute(p);
        CatalogProductSnapshot collision = new CatalogProductSnapshot(2L, p.productCode(), p.title(), p.shortDescription(),
                p.thumbnailUrl(), p.price(), p.currency(), p.publishedAt(), p.content(), null, 2, "2", p.updatedAt());
        assertThatThrownBy(() -> apply.execute(collision)).isInstanceOf(BusinessException.class);
        assertThat(products.count()).isEqualTo(1);
    }
    @Test void startupReadsEveryPageAndCanRestartFromZeroAfterFailure() {
        CatalogProductSnapshot old = product(1, 1, "Old", 1000);
        CatalogProductSnapshot latest = product(1, 2, "Latest", 2000);
        when(catalog.codes(0)).thenReturn(List.of("game-1"));
        when(catalog.detail("game-1")).thenReturn(old);
        when(catalog.codes(1)).thenThrow(new IllegalStateException("Catalog down"));
        assertThatThrownBy(sync::execute).isInstanceOf(IllegalStateException.class);
        assertThat(status("/api/v1/storefront/products")).isEqualTo(503);
        apply.execute(latest);
        doReturn(List.of("game-2")).when(catalog).codes(1);
        when(catalog.detail("game-2")).thenReturn(product(2, 1, "Second", 2000));
        sync.execute();
        assertThat(read.execute("game-1")).isEqualTo(latest);
        assertThat(read.execute("game-2").title()).isEqualTo("Second");
        verify(catalog, times(2)).codes(0);
    }
    @Test void mysqlSearchEscapesWildcardsSupportsKoreanAndHasStableSorting() {
        apply.execute(product(1, 1, "한글 100%_! Game", 1000));
        apply.execute(product(2, 1, "GAME Two", 1000));
        apply.execute(product(3, 1, "Other", 3000));
        assertThat(search.execute("%_!", "NEWEST", 0, 20).getTotalElements()).isEqualTo(1);
        assertThat(search.execute("한글", "NEWEST", 0, 20).getTotalElements()).isEqualTo(1);
        assertThat(search.execute(" game ", "PRICE_ASC", 0, 20).getContent())
                .extracting(CatalogProductSnapshot::productId).containsExactly(2L, 1L);
        assertThat(search.execute(null, "PRICE_DESC", 0, 1).getContent().getFirst().productId()).isEqualTo(3);
        assertThat(search.execute(null, "NEWEST", 1, 2).getTotalElements()).isEqualTo(3);
        assertThat(search.execute(null, "NEWEST", 1, 2).getContent()).hasSize(1);
    }
    @Test void publicApiValidatesInputsAndExposesOnlyPublicData() throws Exception {
        assertThat(status("/api/v1/storefront/products/no-such-game")).isEqualTo(404);
        for (String query : List.of("size=101", "page=-1", "sort=UNKNOWN", "q=" + "x".repeat(101)))
            assertThat(status("/api/v1/storefront/products?" + query)).isEqualTo(400);
        apply.execute(product(1, 1, "Title", 1000));
        assertThat(status("/api/v1/storefront/products/GAME-1")).isEqualTo(404);
        String body = http().get().uri("/api/v1/storefront/products/game-1").retrieve().body(String.class);
        assertThat(body).contains("contentVersion", "buildVersion").doesNotContain("ownerSubject", "storagePath");
        JsonNode page = mapper.readTree(http().get().uri("/api/v1/storefront/products").retrieve().body(String.class));
        assertThat(page.path("data").path("totalElements").asLong()).isEqualTo(1);
        assertThat(page.path("data").path("content").get(0).has("content")).isFalse();
        int postStatus = http().post().uri("/api/v1/storefront/products")
                .exchange((r,s) -> s.getStatusCode().value());
        assertThat(postStatus).isEqualTo(403);
        assertThat(status("/api/v1/ops/dlt")).isEqualTo(403);
    }
    @Test void duplicateKafkaDeliveriesAndOlderVersionAreHarmless() throws Exception {
        listeners.start();
        try {
            CatalogProductPublishedEvent latest = CatalogProductPublishedEvent.of(product(10, 2, "Latest", 2000));
            kafka.send(record(latest, latest.partitionKey())).get(10, TimeUnit.SECONDS);
            kafka.send(record(latest, latest.partitionKey())).get(10, TimeUnit.SECONDS);
            CatalogProductPublishedEvent old = CatalogProductPublishedEvent.of(product(10, 1, "Old", 1000));
            kafka.send(record(old, old.partitionKey())).get(10, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(products.findById(10L)).hasValueSatisfying(p ->
                            assertThat(p.snapshot()).isEqualTo(latest.product())));
        } finally { listeners.stop(); }
    }
    @Test void invalidKafkaInputGoesToDltWithoutWritingProduct() throws Exception {
        CatalogProductPublishedEvent event = CatalogProductPublishedEvent.of(product(99, 1, "Invalid", 1000));
        HashMap<String, Object> config = new HashMap<String,Object>();
        config.put("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id", "store-dlt-" + id()); config.put("auto.offset.reset", "earliest");
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<String,String>(config)) {
            consumer.subscribe(List.of(Topics.CATALOG + ".DLT"));
            listeners.start();
            kafka.send(record(event, "wrong-key")).get(10, TimeUnit.SECONDS);
            boolean found = false; long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (!found && System.nanoTime() < deadline)
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500)))
                    if (r.value().contains(event.eventId())) found = true;
            assertThat(found).isTrue();
            assertThat(products.findById(99L)).isEmpty();
        } finally { listeners.stop(); }
    }
    @Test void schemaContainsOnlyOneBusinessTableAndNoInboxOrOutbox() {
        assertThat(jdbc.queryForList("SHOW TABLES", String.class))
                .contains("store_product", "flyway_schema_history").doesNotContain("processed_event", "outbox_event");
    }
    @Test void openApiMatchesSnapshot() { OpenApiSnapshot.verify(port, "store"); }
}
