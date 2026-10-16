package com.stove.catalog.core.service;

import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import static com.stove.catalog.CatalogFixtures.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.stove.catalog.*;
import com.stove.catalog.core.domain.*;
import com.stove.common.kafka.*;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;

class CatalogPublicationIntegrationTest extends CatalogIntegrationTestBase {
    @Autowired PublishProjectRelease publish;
    @Autowired ReadPublishedProducts read;
    @Autowired CatalogReleaseRepository releases;
    @Autowired CatalogProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockitoSpyBean OutboxRecorder outbox;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private void send(ProjectReleaseRequestedEvent e) {
        publish.execute(ReleaseSubmissionMapper.from(envelope(e), e));
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
        CatalogProductSnapshot latest = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
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
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx ->
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
                        s.storagePath());
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
        new org.springframework.transaction.support.TransactionTemplate(transactions)
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
            } catch (org.springframework.dao.DataAccessException
                    | java.util.NoSuchElementException failure) {
                if (attempt == 5) throw failure;
            }
        }
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
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () ->
                                    assertThat(products.findByProductCode(e.partitionKey()))
                                            .isPresent());
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
        HashMap<String, Object> config = new java.util.HashMap<String, Object>();
        config.put(
                "bootstrap.servers",
                kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id", "catalog-dlt-check-" + id());
        config.put("auto.offset.reset", "earliest");
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put(
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer =
                new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(config)) {
            consumer.subscribe(java.util.List.of(Topics.STUDIO + ".DLT"));
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
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () ->
                                    assertThat(products.findByProductCode(e.partitionKey()))
                                            .isPresent());
        } finally {
            listeners.stop();
        }
    }
}
