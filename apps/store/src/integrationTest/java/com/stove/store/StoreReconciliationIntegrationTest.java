package com.stove.store;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.web.error.BusinessException;
import com.stove.store.core.domain.StoreProductRepository;
import com.stove.store.core.domain.StoreReconciliation;
import com.stove.store.core.domain.StoreReconciliationRepository;
import com.stove.store.core.port.CatalogProducts;
import com.stove.store.core.service.ApplyCatalogProduct;
import com.stove.store.core.service.SynchronizeCatalogProducts;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.store.sync.enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class StoreReconciliationIntegrationTest {
    @Autowired SynchronizeCatalogProducts sync;
    @Autowired ApplyCatalogProduct events;
    @Autowired StoreProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean StoreReconciliationRepository reconciliations;
    @MockitoBean CatalogProducts catalog;

    @BeforeEach void ready() {
        products.deleteAll();
        when(catalog.codes(anyInt())).thenReturn(List.of());
        sync.execute();
    }

    @Test void verifiedCanonicalSnapshotRepairsSameVersionAndRecordsBeforeAndAfterAtomically() throws Exception {
        CatalogProductSnapshot canonical = product(601, 2, "Canonical", 1000);
        events.execute(canonical);
        jdbc.update("UPDATE store_product SET title='Damaged',price=2000,listed=FALSE WHERE product_id=601");
        jdbc.update("UPDATE store_product_genre SET genre_code='RPG' WHERE product_id=601");
        source(canonical);
        assertThatCode(sync::execute).doesNotThrowAnyException();
        assertThat(products.findById(601L).orElseThrow().snapshot()).isEqualTo(canonical);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store_reconciliation WHERE product_id=601 AND action='REPAIRED'", Long.class)).isEqualTo(1);
        String before = jdbc.queryForObject("SELECT before_snapshot FROM store_reconciliation WHERE product_id=601 AND action='REPAIRED' ORDER BY id DESC LIMIT 1", String.class);
        String after = jdbc.queryForObject("SELECT after_snapshot FROM store_reconciliation WHERE product_id=601 AND action='REPAIRED' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(mapper.readTree(before).path("price").asLong()).isEqualTo(2000);
        assertThat(mapper.readTree(after).path("price").asLong()).isEqualTo(1000);
        assertThat(mapper.readTree(before).path("genres").get(0).asText()).isEqualTo("RPG");
        assertThat(mapper.readTree(after).path("genres").get(0).asText()).isEqualTo("ACTION");
        sync.execute();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store_reconciliation WHERE product_id=601 AND action='REPAIRED'", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> events.execute(product(601, 2, "Untrusted same-version event", 3))).isInstanceOf(BusinessException.class);
        assertThat(products.findById(601L).orElseThrow().snapshot()).isEqualTo(canonical);
    }

    @Test void delayedHttpResponseCannotOverwriteANewerKafkaProjection() throws Exception {
        CatalogProductSnapshot original = product(602, 1, "First", 1000);
        CatalogProductSnapshot response = product(602, 2, "HTTP", 2000);
        CatalogProductSnapshot newest = product(602, 3, "New Kafka", 3000);
        events.execute(original);
        source(response);
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(catalog.detail(response.productCode())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            requested.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return response;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(sync::execute);
            assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
            events.execute(newest);
            release.countDown();
            future.get(15, TimeUnit.SECONDS);
            assertThat(products.findById(602L).orElseThrow().snapshot()).isEqualTo(newest);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void failedBackgroundScanPreservesAnAlreadyReadyProjection() {
        events.execute(product(603, 1, "Available", 1000));
        when(catalog.codes(0)).thenThrow(new IllegalStateException("temporary source outage"));
        assertThatThrownBy(sync::execute).isInstanceOf(RuntimeException.class);
        assertThatCode(sync::requireReady).doesNotThrowAnyException();
        assertThat(products.findById(603L)).isPresent();
    }

    @Test void aFailedProductDoesNotStarveOtherProductsInTheSameScan() {
        CatalogProductSnapshot good = product(604, 1, "Recoverable", 1000);
        when(catalog.codes(0)).thenReturn(List.of("bad-product", good.productCode()));
        when(catalog.detail("bad-product")).thenThrow(new IllegalStateException("invalid source response"));
        when(catalog.detail(good.productCode())).thenReturn(good);
        assertThatThrownBy(sync::execute).isInstanceOf(RuntimeException.class);
        assertThat(products.findById(good.productId())).hasValueSatisfying(row -> assertThat(row.snapshot()).isEqualTo(good));
    }

    @Test void canonicalCodeMismatchCannotCreateAnotherProduct() {
        when(catalog.codes(0)).thenReturn(List.of("requested-product"));
        when(catalog.detail("requested-product")).thenReturn(product(605, 1, "Wrong identity", 1000));
        assertThatThrownBy(sync::execute).isInstanceOf(RuntimeException.class);
        assertThat(products.findById(605L)).isEmpty();
    }

    @Test void reconciliationAndAuditRollBackTogetherIfTheAuditCannotBeWritten() {
        CatalogProductSnapshot canonical = product(606, 1, "Original", 1000);
        events.execute(canonical);
        jdbc.update("UPDATE store_product SET price=2000 WHERE product_id=606");
        source(canonical);
        doAnswer(call -> {
            entityManager.flush();
            throw new IllegalStateException("test audit unavailable after product SQL flush");
        }).when(reconciliations).saveAndFlush(any(StoreReconciliation.class));
        assertThatThrownBy(sync::execute).isInstanceOf(RuntimeException.class);
        assertThat(products.findById(606L).orElseThrow().getPrice()).isEqualTo(2000);
    }

    @Test void changedPublicationIdentityIsNeverAutomaticallyRepaired() {
        CatalogProductSnapshot canonical = product(607, 1, "Original", 1000);
        events.execute(canonical);
        jdbc.update("UPDATE store_product SET published_at='2025-01-01' WHERE product_id=607");
        source(canonical);
        assertThatThrownBy(sync::execute).isInstanceOf(RuntimeException.class);
        assertThat(products.findById(607L).orElseThrow().getPublishedAt()).isNotEqualTo(canonical.publishedAt());
    }

    private void source(CatalogProductSnapshot snapshot) {
        when(catalog.codes(0)).thenReturn(List.of(snapshot.productCode()));
        when(catalog.detail(snapshot.productCode())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return snapshot;
        });
    }
}
