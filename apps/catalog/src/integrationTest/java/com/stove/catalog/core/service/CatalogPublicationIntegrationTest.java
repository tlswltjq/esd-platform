package com.stove.catalog.core.service;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static com.stove.catalog.CatalogFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.CatalogIntegrationTestBase;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogPublicationJobRepository;
import com.stove.catalog.core.domain.CatalogReleaseRepository;
import com.stove.catalog.core.domain.PublishedProduct;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.CatalogProductPublishedEvent;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.kafka.contract.payload.ProjectReleaseCompletedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseRejectedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.test.PausePoints;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CatalogPublicationIntegrationTest extends CatalogIntegrationTestBase {
    static final PausePoints POINTS = new PausePoints();
    @TestConfiguration
    static class Pausing {
        @Bean static BeanPostProcessor pausingProducts() {
            return PausePoints.pausing(POINTS, CatalogProductRepository.class);
        }
    }
    @AfterEach void clearPauses() {
        assertThat(POINTS.reset()).as("쓰이지 않은 멈춤 지점").isEmpty();
    }
    @Autowired PublishProjectRelease publish;
    @Autowired ReceiveProjectRelease receive;
    @Autowired CatalogPublicationJobRepository jobs;
    @Autowired PublishQueuedReleases queued;
    @Autowired ReadPublishedProducts read;
    @Autowired CatalogReleaseRepository releases;
    @Autowired CatalogProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockitoSpyBean OutboxRecorder outbox;
    @Autowired PlatformTransactionManager transactions;

    private void send(ProjectReleaseRequestedEvent e) {
        publish.execute(ReleaseSubmissionMapper.from(envelope(e), e));
    }

    private void receive(ProjectReleaseRequestedEvent e) {
        receive.execute(ReleaseSubmissionMapper.from(envelope(e), e));
    }

    private int count(String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?"
                        + (table.equals("outbox_event") ? " AND topic='stove.release.v1'" : ""), Integer.class, value);
    }

    @Test
    void publishesPublicSnapshotOnlyWhenCurrentPublicationChanges() throws Exception {
        ProjectReleaseRequestedEvent first = event(id(), 1);
        send(first);
        send(first);
        send(ProjectReleaseRequestedEvent.of(id(), first.reviewRequestId(), first.snapshot()));
        ProjectReleaseRequestedEvent second = event(first.snapshot().projectId(), 2);
        send(second);
        send(event(first.snapshot().projectId(), 1));
        List<String> payloads = jdbc.queryForList(
                "SELECT payload FROM outbox_event WHERE aggregate_id=? AND event_type='CatalogProductPublished' ORDER BY id",
                String.class, first.partitionKey());
        assertThat(payloads).hasSize(2);
        CatalogProductSnapshot latest = new ObjectMapper().findAndRegisterModules()
                .readValue(payloads.getLast(), CatalogProductPublishedEvent.class).product();
        PublishedProduct detail = read.detail(first.partitionKey());
        assertThat(latest.productId()).isEqualTo(detail.productId());
        assertThat(latest.contentVersion()).isEqualTo(2);
        assertThat(latest.publishedAt()).isEqualTo(detail.publishedAt());
        assertThat(latest.updatedAt()).isEqualTo(detail.updatedAt());
        assertThat(latest.title()).isEqualTo(second.snapshot().title());
        assertThat(payloads.toString()).doesNotContain("ownerSubject", "storagePath", "releaseRequestId", "checksum");
    }

    @Test
    void storeEventFailureAlsoRollsBackPublicationAndStudioResult() {
        ProjectReleaseRequestedEvent e = event(id(), 1);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                doThrow(new IllegalStateException("Store event failed")).when(outbox)
                        .record(eq("CatalogProduct"), eq(e.partitionKey()), any(CatalogProductPublishedEvent.class)));
        try {
            assertThatThrownBy(() -> send(e)).isInstanceOf(IllegalStateException.class);
            assertThat(products.findByProductCode(e.partitionKey())).isEmpty();
            assertThat(releases.findById(e.releaseRequestId())).isEmpty();
            assertThat(count("processed_event", "event_id", e.eventId())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", Integer.class,
                    e.partitionKey())).isZero();
        } finally { reset(outbox); }
        send(e);
        assertThat(read.detail(e.partitionKey()).snapshot()).isEqualTo(e.snapshot());
    }

    @Test
    void publishesReplacesRejectsStaleAndKeepsOriginalResults() {
        long project = id();
        ProjectReleaseRequestedEvent first = event(project, 1);
        send(first);
        ProjectReleaseRequestedEvent second = event(project, 2);
        send(second);
        send(first);
        ProjectReleaseRequestedEvent stale = event(project, 1);
        send(stale);
        assertThat(read.detail(first.partitionKey()).snapshot()).isEqualTo(second.snapshot());
        assertThat(releases.findById(first.releaseRequestId()).orElseThrow().getStatus())
                .isEqualTo("COMPLETED");
        assertThat(releases.findById(stale.releaseRequestId()).orElseThrow().getReason())
                .isEqualTo("STALE_CONTENT_VERSION");
        assertThat(count("outbox_event", "aggregate_id", first.partitionKey())).isEqualTo(3);
        String payload =
                jdbc.queryForObject(
                        "SELECT payload FROM outbox_event WHERE aggregate_id=? AND"
                            + " event_type='ProjectReleaseRejected'",
                        String.class,
                        first.partitionKey());
        assertThat(payload).contains(stale.eventId(), stale.releaseRequestId().toString());
    }

    @Test
    void sameVersionNewRequestCompletesWithoutMovingPublicReference() {
        ProjectReleaseRequestedEvent e = event(id(), 1);
        send(e);
        CatalogProduct before = products.findByProductCode(e.partitionKey()).orElseThrow();
        ProjectReleaseRequestedEvent retry = ProjectReleaseRequestedEvent.of(id(), e.reviewRequestId(), e.snapshot());
        send(retry);
        CatalogProduct after = products.findByProductCode(e.partitionKey()).orElseThrow();
        assertThat(after.getCurrentReleaseRequestId()).isEqualTo(e.releaseRequestId());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(releases.findById(retry.releaseRequestId()).orElseThrow().getStatus())
                .isEqualTo("COMPLETED");
        assertThat(count("outbox_event", "aggregate_id", e.partitionKey())).isEqualTo(2);
    }

    @Test
    void requestAndVersionConflictsDoNotOverwriteOrMarkInbox() {
        ProjectReleaseRequestedEvent e = event(id(), 1);
        send(e);
        ProjectReleaseRequestedEvent conflict =
                ProjectReleaseRequestedEvent.of(
                        e.releaseRequestId(), e.reviewRequestId(), e.snapshot());
        assertThatThrownBy(() -> send(conflict)).isInstanceOf(BusinessException.class);
        assertThat(count("processed_event", "event_id", conflict.eventId())).isZero();
        ProjectSnapshot s = e.snapshot();
        ProjectSnapshot changed =
                new ProjectSnapshot(
                        s.projectId(),
                        s.productCode(),
                        s.ownerSubject(),
                        s.contentVersion(),
                        "Changed",
                        s.shortDescription(),
                        s.thumbnailUrl(),
                        null,
                        s.content(),
                        s.price(),
                        s.currency(),
                        s.buildId(),
                        s.version(),
                        s.fileSize(),
                        s.checksum(),
                        s.storagePath(),
                        s.genres()
                );
        ProjectReleaseRequestedEvent sameVersion = ProjectReleaseRequestedEvent.of(id(), id(), changed);
        assertThatThrownBy(() -> send(sameVersion)).isInstanceOf(BusinessException.class);
        ProjectReleaseRequestedEvent sameEvent =
                new ProjectReleaseRequestedEvent(
                        e.eventId(),
                        e.occurredAt(),
                        e.releaseRequestId(),
                        e.reviewRequestId(),
                        changed);
        assertThatThrownBy(() -> send(sameEvent)).isInstanceOf(BusinessException.class);
        assertThat(read.detail(e.partitionKey()).snapshot()).isEqualTo(s);
        assertThat(count("processed_event", "event_id", sameVersion.eventId())).isZero();
    }

    @Test
    void invalidEnvelopeNeverMarksInbox() {
        ProjectReleaseRequestedEvent e = event(id(), 1);
        assertThatThrownBy(
                        () ->
                                publish.execute(ReleaseSubmissionMapper.from(new EventEnvelope(
                                                e.eventId(), e.eventType(), "wrong", json(e)), e)))
                .isInstanceOf(BusinessException.class);
        assertThat(count("processed_event", "event_id", e.eventId())).isZero();
    }

    @Test
    void outboxFailureRollsBackAllPublicationWritesAndRetrySucceeds() {
        ProjectReleaseRequestedEvent e = event(id(), 1);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx ->
                                doThrow(new IllegalStateException("outbox unavailable"))
                                        .when(outbox)
                                        .record(eq("CatalogProduct"), eq(e.partitionKey()), any(ProjectReleaseCompletedEvent.class)));
        assertThatThrownBy(() -> send(e)).isInstanceOf(IllegalStateException.class);
        assertThat(releases.findById(e.releaseRequestId())).isEmpty();
        assertThat(products.findByProductCode(e.partitionKey())).isEmpty();
        assertThat(count("processed_event", "event_id", e.eventId())).isZero();
        reset(outbox);
        send(e);
        assertThat(read.detail(e.partitionKey()).snapshot()).isEqualTo(e.snapshot());
    }

    @Test
    void concurrentFirstPublicationAndDuplicateDeliveriesConvergeToHighestVersion()
            throws Exception {
        long project = id();
        ProjectReleaseRequestedEvent low = event(project, 1);
        ProjectReleaseRequestedEvent high = event(project, 2);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Void> a = pool.submit(() -> retry(start, low));
            Future<Void> b = pool.submit(() -> retry(start, high));
            Future<Void> c = pool.submit(() -> retry(start, high));
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            c.get(30, TimeUnit.SECONDS);
            assertThat(read.detail(high.partitionKey()).snapshot()).isEqualTo(high.snapshot());
            assertThat(count("catalog_product", "project_id", project)).isEqualTo(1);
            assertThat(count("outbox_event", "aggregate_id", high.partitionKey())).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private Void retry(CountDownLatch start, ProjectReleaseRequestedEvent e) throws Exception {
        start.await();
        for (int attempt = 0; ; attempt++) {
            try {
                send(e);
                return null;
            } catch (DataAccessException
                    | NoSuchElementException failure) {
                if (attempt == 5) throw failure;
            }
        }
    }

    @Tag("known-defect")
    @Test
    @DisplayName("[D-042] 빈 Project 잠금 조회 뒤 같은 Project의 최초 공개가 커밋돼도 높은 버전으로 수렴한다")
    void sameProjectFirstPublicationCommittedBetweenProjectAndCodeReadsConverges() throws Exception {
        // 기대: v1 첫 시도는 재시도할 경합으로 롤백되고, 같은 요청을 다시 처리하면 STALE로 거절돼 v2가 남는다.
        // 실제(수정 전): 같은 Project의 상품을 다른 Project로 오인해 CONFLICT. 영향: 정상 공개 작업이 계약 위반 사유로 실패한다.
        long project = id();
        ProjectReleaseRequestedEvent low = event(project, 1);
        ProjectReleaseRequestedEvent high = event(project, 2);
        PausePoints.Gate gate = POINTS.arm("CatalogProductRepository.lockByProjectId", "low-publication");
        PausePoints.Run<Void> publishing = PausePoints.start("low-publication", () -> {
            send(low);
            return null;
        });
        try {
            gate.awaitArrival();
            assertThat(count("catalog_product", "project_id", project)).isZero();
            send(high);
            assertThat(count("catalog_product", "project_id", project)).isEqualTo(1);
        } finally { gate.release(); }
        try {
            publishing.join();
        } catch (ConcurrencyFailureException lostFirstInsert) {
            // 운영은 PublishQueuedReleases가 같은 요청을 새 트랜잭션으로 다시 처리한다.
            assertThat(releases.findById(low.releaseRequestId())).isEmpty();
            assertThat(count("processed_event", "event_id", low.eventId())).isZero();
            send(low);
        }
        assertThat(read.detail(high.partitionKey()).snapshot()).isEqualTo(high.snapshot());
        assertThat(count("catalog_product", "project_id", project)).isEqualTo(1);
        assertThat(releases.findById(high.releaseRequestId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(releases.findById(low.releaseRequestId()).orElseThrow().getReason())
                .isEqualTo("STALE_CONTENT_VERSION");
        assertThat(jdbc.queryForList(
                "SELECT CONCAT(topic, ' ', event_type, ' ', partition_key) FROM outbox_event WHERE aggregate_id=? ORDER BY id",
                String.class, high.partitionKey())).containsExactly(
                Topics.CATALOG + " CatalogProductPublished " + high.partitionKey(),
                Topics.RELEASE + " ProjectReleaseCompleted " + high.partitionKey(),
                Topics.RELEASE + " ProjectReleaseRejected " + high.partitionKey());
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        CatalogProductSnapshot published = json.readValue(
                outboxPayload(high.partitionKey(), "CatalogProductPublished"), CatalogProductPublishedEvent.class).product();
        assertThat(published.contentVersion()).isEqualTo(2);
        assertThat(published.title()).isEqualTo(high.snapshot().title());
        ProjectReleaseCompletedEvent completed = json.readValue(
                outboxPayload(high.partitionKey(), "ProjectReleaseCompleted"), ProjectReleaseCompletedEvent.class);
        assertThat(completed.releaseRequestId()).isEqualTo(high.releaseRequestId());
        assertThat(completed.requestEventId()).isEqualTo(high.eventId());
        ProjectReleaseRejectedEvent rejected = json.readValue(
                outboxPayload(high.partitionKey(), "ProjectReleaseRejected"), ProjectReleaseRejectedEvent.class);
        assertThat(rejected.releaseRequestId()).isEqualTo(low.releaseRequestId());
        assertThat(rejected.requestEventId()).isEqualTo(low.eventId());
        assertThat(rejected.reason()).isEqualTo("STALE_CONTENT_VERSION");
    }

    @Tag("known-defect")
    @Test
    @DisplayName("[D-042] 출시 접수의 빈 Project 잠금 조회 뒤 같은 Project의 최초 공개가 커밋돼도 접수해 높은 버전으로 수렴한다")
    void receiptConvergesWhenSameProjectFirstPublicationCommitsBetweenProjectAndCodeReads() throws Exception {
        // 기대: v2 접수 첫 시도는 재시도할 경합으로 롤백되고, 재배달하면 접수돼 v2가 공개된다.
        // 실제(수정 전): 같은 Project의 상품을 다른 Project로 오인해 CONFLICT. 영향: 정상 접수가 계약 위반으로 실패한다.
        long project = id();
        ProjectReleaseRequestedEvent low = event(project, 1);
        ProjectReleaseRequestedEvent high = event(project, 2);
        PausePoints.Gate gate = POINTS.arm("CatalogProductRepository.lockByProjectId", "high-receipt");
        PausePoints.Run<Void> receiving = PausePoints.start("high-receipt", () -> {
            receive(high);
            return null;
        });
        try {
            gate.awaitArrival();
            assertThat(count("catalog_product", "project_id", project)).isZero();
            send(low);
            assertThat(count("catalog_product", "project_id", project)).isEqualTo(1);
        } finally { gate.release(); }
        try {
            receiving.join();
        } catch (ConcurrencyFailureException lostFirstInsert) {
            // 운영은 Kafka DefaultErrorHandler가 같은 레코드를 다시 배달한다.
            assertThat(jobs.findById(high.releaseRequestId())).isEmpty();
            assertThat(count("processed_event", "event_id", high.eventId())).isZero();
            receive(high);
        }
        assertThat(jobs.findById(high.releaseRequestId())).isPresent();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            queued.execute();
            assertThat(read.detail(high.partitionKey()).snapshot()).isEqualTo(high.snapshot());
        });
        assertThat(count("catalog_product", "project_id", project)).isEqualTo(1);
        assertThat(releases.findById(high.releaseRequestId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("[D-042] 다른 Project가 같은 상품 코드를 쓰면 경합 재시도가 아니라 CONFLICT로 거절한다")
    void differentProjectWithSameProductCodeStaysConflict() {
        ProjectReleaseRequestedEvent owner = event(id(), 1);
        send(owner);
        ProjectSnapshot s = owner.snapshot();
        ProjectSnapshot other = new ProjectSnapshot(id(), s.productCode(), s.ownerSubject(), s.contentVersion(), s.title(),
                s.shortDescription(), s.thumbnailUrl(), s.videoUrl(), s.content(), s.price(), s.currency(), s.buildId(),
                s.version(), s.fileSize(), s.checksum(), s.storagePath(), s.genres());
        ProjectReleaseRequestedEvent intruder = ProjectReleaseRequestedEvent.of(id(), id(), other);
        assertThatThrownBy(() -> receive(intruder)).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT)).hasMessage("상품 코드가 다른 Project에 연결되어 있습니다.");
        assertThatThrownBy(() -> send(intruder)).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT)).hasMessage("상품 코드가 다른 Project에 연결되어 있습니다.");
        assertThat(jobs.findById(intruder.releaseRequestId())).isEmpty();
        assertThat(releases.findById(intruder.releaseRequestId())).isEmpty();
        assertThat(count("processed_event", "event_id", intruder.eventId())).isZero();
        assertThat(products.findByProductCode(s.productCode()).orElseThrow().getProjectId()).isEqualTo(s.projectId());
    }

    private String outboxPayload(String productCode, String eventType) {
        return jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id=? AND event_type=?",
                String.class, productCode, eventType);
    }

    @Test
    void kafkaConsumesWithNewGroupAndDeduplicates() throws Exception {
        ProjectReleaseRequestedEvent e = event(id(), 1);
        listeners.start();
        try {
            ProducerRecord<String, String> record =
                    new ProducerRecord<String, String>(Topics.STUDIO, e.partitionKey(), json(e));
            record.headers()
                    .add(EventHeaders.EVENT_ID, e.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers()
                    .add(EventHeaders.EVENT_TYPE, e.eventType().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(10, TimeUnit.SECONDS);
            kafka.send(record).get(10, TimeUnit.SECONDS);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                queued.execute();
                                assertThat(products.findByProductCode(e.partitionKey())).isPresent();
                            });
            assertThat(count("outbox_event", "aggregate_id", e.partitionKey())).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT consumer_group FROM processed_event WHERE event_id=?",
                                    String.class,
                                    e.eventId()))
                    .isEqualTo(PublishProjectRelease.CONSUMER_GROUP);
        } finally {
            listeners.stop();
        }
    }

    @Test
    void invalidKafkaContractGoesToDltAndOriginalEventCanBeReplayedAfterRepair() throws Exception {
        ProjectReleaseRequestedEvent e = event(id(), 1);
        HashMap<String, Object> config = new HashMap<String, Object>();
        config.put(
                "bootstrap.servers",
                kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id", "catalog-dlt-check-" + id());
        config.put("auto.offset.reset", "earliest");
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put(
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<String, String>(config)) {
            consumer.subscribe(List.of(Topics.STUDIO + ".DLT"));
            listeners.start();
            ProducerRecord<String, String> bad = new ProducerRecord<String, String>(Topics.STUDIO, "wrong-key", json(e));
            bad.headers().add(EventHeaders.EVENT_ID, e.eventId().getBytes(StandardCharsets.UTF_8));
            bad.headers()
                    .add(EventHeaders.EVENT_TYPE, e.eventType().getBytes(StandardCharsets.UTF_8));
            kafka.send(bad).get(10, TimeUnit.SECONDS);
            boolean received = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (!received && System.nanoTime() < deadline)
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500)))
                    if (record.value().contains(e.eventId())) received = true;
            assertThat(received).isTrue();
            assertThat(releases.findById(e.releaseRequestId())).isEmpty();
            assertThat(count("processed_event", "event_id", e.eventId())).isZero();
            ProducerRecord<String, String> repaired =
                    new ProducerRecord<String, String>(Topics.STUDIO, e.partitionKey(), json(e));
            repaired.headers()
                    .add(EventHeaders.EVENT_ID, e.eventId().getBytes(StandardCharsets.UTF_8));
            repaired.headers()
                    .add(EventHeaders.EVENT_TYPE, e.eventType().getBytes(StandardCharsets.UTF_8));
            kafka.send(repaired).get(10, TimeUnit.SECONDS);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> {
                                queued.execute();
                                assertThat(products.findByProductCode(e.partitionKey())).isPresent();
                            });
        } finally {
            listeners.stop();
        }
    }
}
