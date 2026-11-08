package com.stove.store;

import static com.stove.store.StoreFixtures.id;
import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.CatalogProductPublishedEvent;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.test.PausePoints;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.testcontainers.SharedContainers;
import com.stove.common.web.error.BusinessException;
import com.stove.store.api.listener.CatalogProductMapper;
import com.stove.store.core.domain.StoreProduct;
import com.stove.store.core.domain.StoreProductRepository;
import com.stove.store.core.port.CatalogProducts;
import com.stove.store.core.service.ApplyCatalogProduct;
import com.stove.store.core.service.ReadStoreProduct;
import com.stove.store.core.service.ReconcileCatalogProduct;
import com.stove.store.core.service.SearchStoreProducts;
import com.stove.store.core.service.SynchronizeCatalogProducts;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.store.sync.enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, StoreIntegrationTest.Pausing.class})
class StoreIntegrationTest {
    static final PausePoints POINTS = new PausePoints();
    @TestConfiguration
    static class Pausing {
        @Bean static BeanPostProcessor pausingProducts() {
            return PausePoints.pausing(POINTS, StoreProductRepository.class);
        }
    }
    @AfterEach void clearPauses() {
        assertThat(POINTS.reset()).as("쓰이지 않은 멈춤 지점").isEmpty();
    }
    @MockitoSpyBean ApplyCatalogProduct apply;
    @Autowired SynchronizeCatalogProducts sync;
    @Autowired ReconcileCatalogProduct reconcile;
    @Autowired SearchStoreProducts search;
    @Autowired ReadStoreProduct read;
    @Autowired StoreProductRepository products;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
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
    // Prometheus 수집 경로가 보안에서 열려 있는지 검증한다. Store 는 보호 대상이 아닌 경로를
    // denyAll 로 막으므로(실제로 /actuator 는 403), 이 경로가 403/401 이 아니라는 것이
    // 곧 "보안 허용 목록에 들었다" 는 뜻이다. 엔드포인트 핸들러 자체의 등록(200·본문)은
    // 운영 런타임 여정(runtime-auto)에서 확인한다 -- scrape 엔드포인트 빈은 운영 runtimeOnly
    // 레지스트리에서만 조립되고 통합 테스트 컨텍스트에는 없어, 여기서는 보안 경계만 책임진다.
    @Test void prometheusScrapePathIsNotBlockedBySecurity() {
        int status = status("/actuator/prometheus");
        assertThat(status).isNotEqualTo(401);
        assertThat(status).isNotEqualTo(403);
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

    @Test void stoppedSalesAtTheSameContentVersionSurviveInitialSyncAndOlderEvents() throws Exception {
        CatalogProductSnapshot original = product(42, 1, "Sales status", 1000);
        CatalogProductSnapshot stopped = new CatalogProductSnapshot(
                original.productId(),
                original.productCode(),
                original.title(),
                original.shortDescription(),
                original.thumbnailUrl(),
                original.price(),
                original.currency(),
                original.publishedAt(),
                original.content(),
                original.videoUrl(),
                original.contentVersion(),
                original.buildVersion(),
                original.updatedAt(),
                "STOPPED",
                2,
                "ON_SALE".equals("STOPPED"),
                original.genres()
        );
        apply.execute(stopped);
        when(catalog.codes(0)).thenReturn(List.of(original.productCode()));
        when(catalog.codes(1)).thenReturn(List.of());
        when(catalog.detail(original.productCode())).thenReturn(original);
        sync.execute();
        assertThat(products.findByProductCode(original.productCode()).orElseThrow().snapshot()).isEqualTo(stopped);
        assertThat(status("/api/v1/storefront/products/" + original.productCode())).isEqualTo(404);
        listeners.start();
        try {
            clearInvocations(apply);
            CatalogProductPublishedEvent old = CatalogProductPublishedEvent.of(original);
            kafka.send(record(old, old.partitionKey())).get(10, TimeUnit.SECONDS);
            verify(apply, timeout(10000)).execute(original);
            assertThat(products.findByProductCode(original.productCode()).orElseThrow().snapshot()).isEqualTo(stopped);
        } finally { listeners.stop(); }
        assertThatThrownBy(() -> jdbc.update("UPDATE store_product SET sale_status='UNKNOWN' WHERE product_id=42"))
                .isInstanceOfSatisfying(UncategorizedSQLException.class,
                        failure -> assertThat(failure.getSQLException().getErrorCode()).isEqualTo(3819));
        assertThat(products.findByProductCode(original.productCode()).orElseThrow().snapshot()).isEqualTo(stopped);
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
    @Test
    @DisplayName("[D-040] 빈 ID 조회 뒤 같은 상품이 커밋돼도 높은 버전으로 수렴한다")
    void sameProductCommittedBetweenIdAndCodeReadsConverges() throws Exception {
        // 기대: 같은 ID의 버전 3 반영. 실제: 다른 상품으로 오인해 CONFLICT. 영향: 사본 갱신 실패.
        PausePoints.Gate gate = POINTS.arm("StoreProductRepository.lockById", "late-insert");
        PausePoints.Run<Void> updating = PausePoints.start("late-insert", () -> {
            apply.execute(product(1, 3, "Latest", 3000));
            return null;
        });
        try {
            gate.awaitArrival();
            assertThat(products.count()).isZero();
            apply.execute(product(1, 1, "Old", 1000));
            assertThat(read.execute("game-1").contentVersion()).isEqualTo(1);
        } finally { gate.release(); }
        updating.join();
        assertThat(read.execute("game-1")).isEqualTo(product(1, 3, "Latest", 3000));
        assertThat(products.count()).isEqualTo(1);
    }
    @Test
    @DisplayName("[D-040] HTTP 정본 대조 중 이벤트가 같은 상품을 생성해도 높은 버전으로 수렴한다")
    void reconciliationConvergesWhenEventCommitsBetweenIdAndCodeReads() throws Exception {
        // 기대: 정본 v3 반영. 실제: 같은 ID를 다른 상품으로 오인. 영향: HTTP 동기화 회차 실패.
        CatalogProductSnapshot latest = product(1, 3, "Latest", 3000);
        when(catalog.detail("game-1")).thenReturn(latest);
        PausePoints.Gate gate = POINTS.arm("StoreProductRepository.lockById", "reconcile-insert");
        PausePoints.Run<Void> updating = PausePoints.start("reconcile-insert", () -> {
            reconcile.execute("game-1");
            return null;
        });
        try {
            gate.awaitArrival();
            assertThat(products.count()).isZero();
            apply.execute(product(1, 1, "Old", 1000));
            assertThat(read.execute("game-1").contentVersion()).isEqualTo(1);
        } finally { gate.release(); }
        updating.join();
        assertThat(read.execute("game-1")).isEqualTo(latest);
        assertThat(products.count()).isEqualTo(1);
    }
    @Test
    @DisplayName("[D-040] 실제 Kafka 배달은 최초 삽입 경합 뒤에도 높은 버전으로 수렴한다")
    void kafkaDeliveryConvergesWhenSameProductIsCommittedBetweenReads() throws Exception {
        CatalogProductSnapshot latest = product(1, 3, "Latest", 3000);
        doAnswer(invocation -> {
            Thread listener = Thread.currentThread();
            String originalName = listener.getName();
            listener.setName("kafka-insert");
            try { return invocation.callRealMethod(); }
            finally { listener.setName(originalName); }
        }).when(apply).execute(latest);
        PausePoints.Gate gate = POINTS.arm("StoreProductRepository.lockById", "kafka-insert");
        listeners.start();
        try {
            CatalogProductPublishedEvent event = CatalogProductPublishedEvent.of(latest);
            kafka.send(record(event, event.partitionKey())).get(10, TimeUnit.SECONDS);
            try {
                gate.awaitArrival();
                apply.execute(product(1, 1, "Old", 1000));
            } finally { gate.release(); }
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(read.execute("game-1")).isEqualTo(latest));
            assertThat(products.count()).isEqualTo(1);
        } finally { gate.release(); listeners.stop(); }
    }
    @Test
    @Tag("known-defect")
    @DisplayName("[D-047] 이벤트 최초 삽입 flush가 MySQL 교착(1213) 희생자가 되어도 새 트랜잭션에서 재시도해 수렴한다")
    void eventInsertDeadlockVictimRetries() throws Exception {
        // 기대: 같은 상품을 동시에 처음 넣다 한쪽이 flush에서 교착 희생자가 되면 재시도해 둘 다 성공.
        // 실제(의심): 1213이 jakarta.persistence 예외로 와 재시도 catch를 지나쳐 실패. 영향: 이벤트 반영 실패.
        long deadlocksBefore = deadlocks();
        insertBothWhileIdIsHeld(() -> { apply.execute(product(1, 1, "First", 1000)); return null; },
                () -> { apply.execute(product(1, 2, "Second", 2000)); return null; });
        assertThat(deadlocks()).as("교착이 실제로 났다").isGreaterThan(deadlocksBefore);
        assertThat(read.execute("game-1").contentVersion()).isEqualTo(2);
        assertThat(products.count()).isEqualTo(1);
    }
    @Test
    @Tag("known-defect")
    @DisplayName("[D-047] HTTP 정본 대조의 최초 삽입 flush가 MySQL 교착(1213) 희생자가 되어도 재시도해 수렴한다")
    void reconciliationInsertDeadlockVictimRetries() throws Exception {
        // 기대: 동시 대조 두 회차가 같은 상품을 처음 넣다 한쪽이 교착 희생자가 되면 재시도해 둘 다 성공.
        // 실제(의심): 1213이 재시도 catch를 지나쳐 대조 회차 실패. 영향: HTTP 자동 복구 회차 실패.
        CatalogProductSnapshot latest = product(1, 2, "Latest", 2000);
        when(catalog.detail("game-1")).thenReturn(latest);
        long deadlocksBefore = deadlocks();
        insertBothWhileIdIsHeld(() -> reconcile.execute("game-1"), () -> reconcile.execute("game-1"));
        assertThat(deadlocks()).as("교착이 실제로 났다").isGreaterThan(deadlocksBefore);
        assertThat(read.execute("game-1")).isEqualTo(latest);
        assertThat(products.count()).isEqualTo(1);
    }
    /**
     * MySQL의 세 세션 중복 삽입 교착을 flush에서 만든다. 두 작업을 "없음" 확인(lockById·findByProductCode) 뒤에
     * 세운 사이, 세 번째 트랜잭션이 같은 상품 ID를 삽입한 채 커밋하지 않는다. 두 작업의 INSERT는 그 행의 공유
     * 잠금을 기다리고, 세 번째가 롤백되면 둘 다 공유 잠금을 얻은 채 배타 잠금을 요청해 교착이 난다. InnoDB는
     * 한쪽을 1213으로 되돌린다(READ COMMITTED라 "없음" 확인은 틈 잠금을 남기지 않는다).
     */
    private void insertBothWhileIdIsHeld(Callable<?> first, Callable<?> second) throws Exception {
        CatalogProductSnapshot holder = withCode(product(1, 9, "Holder", 1), "holder-1");
        // 한 지점에는 하나만 세울 수 있어 두 작업을 "없음" 확인의 서로 다른 단계 뒤에 세운다. 둘 다 아직 INSERT 전이다.
        PausePoints.Gate firstGate = POINTS.arm("StoreProductRepository.lockById", "insert-first");
        PausePoints.Gate secondGate = POINTS.arm("StoreProductRepository.findByProductCode", "insert-second");
        PausePoints.Run<?> firstRun = PausePoints.start("insert-first", first);
        PausePoints.Run<?> secondRun = PausePoints.start("insert-second", second);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holderThread = Executors.newSingleThreadExecutor();
        try {
            firstGate.awaitArrival();
            secondGate.awaitArrival();
            Future<?> holding = holderThread.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                entityManager.persist(StoreProduct.create(holder));
                entityManager.flush();
                held.countDown();
                awaitLatch(release);
                status.setRollbackOnly();
            }));
            assertThat(held.await(30, TimeUnit.SECONDS)).as("같은 ID를 잡은 삽입").isTrue();
            firstGate.release();
            secondGate.release();
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> lockWaits() >= 2);
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
            firstRun.join();
            secondRun.join();
        } finally {
            firstGate.release();
            secondGate.release();
            release.countDown();
            holderThread.shutdownNow();
        }
    }
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("해제 신호가 오지 않았다");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
    private static CatalogProductSnapshot withCode(CatalogProductSnapshot s, String code) {
        return new CatalogProductSnapshot(s.productId(), code, s.title(), s.shortDescription(), s.thumbnailUrl(),
                s.price(), s.currency(), s.publishedAt(), s.content(), s.videoUrl(), s.contentVersion(),
                s.buildVersion(), s.updatedAt(), s.saleStatus(), s.catalogVersion(), s.listed(), s.genres());
    }
    /** 잠금 대기·교착 수는 서버 전체 표라 PROCESS 권한이 있는 root로 읽는다. */
    private static long lockWaits() throws Exception {
        return rootQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits");
    }
    private static long deadlocks() throws Exception {
        return rootQuery("SELECT SUM_ERROR_RAISED FROM performance_schema.events_errors_summary_global_by_error"
                + " WHERE ERROR_NUMBER = 1213");
    }
    private static long rootQuery(String sql) throws Exception {
        try (Connection root = DriverManager.getConnection(SharedContainers.MYSQL.getJdbcUrl(), "root",
                SharedContainers.MYSQL.getPassword());
             Statement statement = root.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
    @Test void invalidEnvelopeAndIdentityConflictDoNotChangeProjection() {
        CatalogProductSnapshot p = product(1, 1, "Title", 1000);
        CatalogProductPublishedEvent event = CatalogProductPublishedEvent.of(p);
        assertThatThrownBy(() -> apply.execute(CatalogProductMapper.from(new EventEnvelope(event.eventId(), event.eventType(), "wrong", ""), event)))
                .isInstanceOf(BusinessException.class);
        assertThat(products.count()).isZero();
        apply.execute(p);
        CatalogProductSnapshot collision = new CatalogProductSnapshot(
                2L,
                p.productCode(),
                p.title(),
                p.shortDescription(),
                p.thumbnailUrl(),
                p.price(),
                p.currency(),
                p.publishedAt(),
                p.content(),
                null,
                2,
                "2",
                p.updatedAt(),
                "ON_SALE",
                2,
                true,
                List.of("ACTION")
        );
        assertThatThrownBy(() -> apply.execute(collision)).isInstanceOf(BusinessException.class);
        when(catalog.detail(p.productCode())).thenReturn(collision);
        assertThatThrownBy(() -> reconcile.execute(p.productCode())).isInstanceOf(BusinessException.class);
        assertThat(read.execute(p.productCode())).isEqualTo(p);
        assertThat(products.count()).isEqualTo(1);
    }
    @Test void reconciliationReadsEveryPageAndKeepsAlreadyReadyDataAfterFailure() {
        CatalogProductSnapshot old = product(1, 1, "Old", 1000);
        CatalogProductSnapshot latest = product(1, 2, "Latest", 2000);
        when(catalog.codes(0)).thenReturn(List.of("game-1"));
        when(catalog.detail("game-1")).thenReturn(old);
        when(catalog.codes(1)).thenThrow(new IllegalStateException("Catalog down"));
        assertThatThrownBy(sync::execute).isInstanceOf(IllegalStateException.class);
        assertThat(status("/api/v1/storefront/products")).isEqualTo(200);
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
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(products.findById(10L)).hasValueSatisfying(p ->
                            assertThat(p.snapshot()).isEqualTo(latest.product())));
        } finally { listeners.stop(); }
    }

    @Test void administratorRestrictionsAndReleaseReachTheActualKafkaDatabaseAndPublicView() throws Exception {
        CatalogProductSnapshot original = product(77, 1, "Platform restriction", 1000);
        CatalogProductSnapshot blocked = new CatalogProductSnapshot(
                original.productId(),
                original.productCode(),
                original.title(),
                original.shortDescription(),
                original.thumbnailUrl(),
                original.price(),
                original.currency(),
                original.publishedAt(),
                original.content(),
                original.videoUrl(),
                original.contentVersion(),
                original.buildVersion(),
                original.updatedAt(),
                "BLOCKED",
                2,
                "ON_SALE".equals("BLOCKED"),
                original.genres()
        );
        CatalogProductSnapshot resumed = new CatalogProductSnapshot(
                original.productId(),
                original.productCode(),
                original.title(),
                original.shortDescription(),
                original.thumbnailUrl(),
                original.price(),
                original.currency(),
                original.publishedAt(),
                original.content(),
                original.videoUrl(),
                original.contentVersion(),
                original.buildVersion(),
                original.updatedAt(),
                "ON_SALE",
                3,
                "ON_SALE".equals("ON_SALE"),
                original.genres()
        );
        listeners.start();
        try {
            CatalogProductPublishedEvent restriction = CatalogProductPublishedEvent.of(blocked);
            kafka.send(record(restriction, restriction.partitionKey())).get(10, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(products.findById(original.productId())).hasValueSatisfying(p ->
                            assertThat(p.snapshot()).isEqualTo(blocked)));
            assertThat(status("/api/v1/storefront/products/" + original.productCode())).isEqualTo(404);
            CatalogProductPublishedEvent release = CatalogProductPublishedEvent.of(resumed);
            kafka.send(record(release, release.partitionKey())).get(10, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(products.findById(original.productId())).hasValueSatisfying(p ->
                            assertThat(p.snapshot()).isEqualTo(resumed)));
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
    @Test void listingFilterRunsBeforeSearchPaginationAndHiddenDetailIsRejected() {
        CatalogProductSnapshot first = product(101, 1, "Listing first", 1000);
        CatalogProductSnapshot hidden = listing(product(102, 1, "Listing hidden", 1), false, 2, "ON_SALE");
        CatalogProductSnapshot stoppedButListed = listing(product(103, 1, "Listing stopped", 500), true, 2, "STOPPED");
        apply.execute(first); apply.execute(hidden); apply.execute(stoppedButListed);
        assertThat(search.execute("Listing", "PRICE_ASC", 0, 1).getTotalElements()).isEqualTo(2);
        assertThat(search.execute("Listing", "PRICE_ASC", 0, 1).getContent()).extracting(CatalogProductSnapshot::productId).containsExactly(103L);
        assertThat(search.execute("Listing", "PRICE_ASC", 1, 1).getContent()).extracting(CatalogProductSnapshot::productId).containsExactly(101L);
        assertThat(search.execute("hidden", "NEWEST", 0, 20).getTotalElements()).isZero();
        assertThat(status("/api/v1/storefront/products/" + hidden.productCode())).isEqualTo(404);
        apply.execute(listing(hidden, true, 3, "ON_SALE"));
        assertThat(status("/api/v1/storefront/products/" + hidden.productCode())).isEqualTo(200);
    }
    @Test void kafkaListingUpdatesAndInitializationKeepTheNewerHiddenProjection() throws Exception {
        CatalogProductSnapshot original = product(104, 1, "Listing Kafka", 1000);
        CatalogProductSnapshot hidden = listing(original, false, 3, "ON_SALE");
        apply.execute(hidden);
        when(catalog.codes(0)).thenReturn(List.of(original.productCode()));
        when(catalog.codes(1)).thenReturn(List.of());
        when(catalog.detail(original.productCode())).thenReturn(original);
        sync.execute();
        assertThat(search.execute("Listing Kafka", "NEWEST", 0, 20).getTotalElements()).isZero();
        listeners.start();
        try {
            CatalogProductPublishedEvent old = CatalogProductPublishedEvent.of(original);
            kafka.send(record(old, old.partitionKey())).get(10, TimeUnit.SECONDS);
            CatalogProductSnapshot shown = listing(original, true, 4, "STOPPED");
            CatalogProductPublishedEvent latest = CatalogProductPublishedEvent.of(shown);
            kafka.send(record(latest, latest.partitionKey())).get(10, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(products.findById(104L)).hasValueSatisfying(p -> assertThat(p.snapshot()).isEqualTo(shown)));
            assertThat(search.execute("Listing Kafka", "NEWEST", 0, 20).getTotalElements()).isEqualTo(1);
        } finally { listeners.stop(); }
    }
    private CatalogProductSnapshot listing(CatalogProductSnapshot p, boolean listed, long version, String sale) {
        return new CatalogProductSnapshot(
                p.productId(),
                p.productCode(),
                p.title(),
                p.shortDescription(),
                p.thumbnailUrl(),
                p.price(),
                p.currency(),
                p.publishedAt(),
                p.content(),
                p.videoUrl(),
                p.contentVersion(),
                p.buildVersion(),
                p.updatedAt(),
                sale,
                version,
                listed,
                p.genres()
        );
    }

    @Test void schemaContainsOnlyOneBusinessTableAndNoInboxOrOutbox() {
        assertThat(jdbc.queryForList("SHOW TABLES", String.class))
                .contains("store_product", "flyway_schema_history").doesNotContain("processed_event", "outbox_event");
    }
    @Test void openApiMatchesSnapshot() { OpenApiSnapshot.verify(port, "store"); }
}
