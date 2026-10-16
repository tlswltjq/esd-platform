package com.stove.review.core.service;

import com.stove.review.api.listener.ReviewSubmissionMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.stove.common.kafka.*;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.review.*;
import com.stove.review.core.domain.*;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class ProjectReviewIntegrationTest extends ReviewIntegrationTestBase {
    @Autowired ReceiveProjectReview receive;
    @Autowired ReadProjectReviews read;
    @Autowired DecideProjectReview decide;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoSpyBean OutboxRecorder outbox;

    private long id() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1);
    }

    private ProjectReviewRequestedEvent submit(long id, long project) {
        var e = ReviewFixtures.event(id, project);
        receive.execute(ReviewSubmissionMapper.from(ReviewFixtures.envelope(e), e));
        return e;
    }

    @Test
    void duplicateReceptionPreservesTheSnapshotAndIndependentRerequests() {
        long id = id(), project = id();
        var event = submit(id, project);
        receive.execute(ReviewSubmissionMapper.from(ReviewFixtures.envelope(event), event));
        decide.execute(id, "reviewer", ReviewStatus.REJECTED, "Fix");
        var duplicate = ProjectReviewRequestedEvent.of(id, event.snapshot());
        receive.execute(ReviewSubmissionMapper.from(ReviewFixtures.envelope(duplicate), duplicate));
        assertThat(read.get(id).getStatus()).isEqualTo(ReviewStatus.REJECTED);
        var newRequest = submit(id(), project);
        assertThat(read.list(null, project, 0, 20).getTotalElements()).isEqualTo(2);
        assertThat(read.get(newRequest.reviewRequestId()).getStatus())
                .isEqualTo(ReviewStatus.REQUESTED);
        var changed =
                new ProjectSnapshot(
                        project,
                        event.snapshot().productCode(),
                        "other-owner",
                        1,
                        "Changed",
                        "Summary",
                        "https://example.com/a.png",
                        null,
                        "Content",
                        1000,
                        "KRW",
                        1L,
                        "1",
                        10,
                        "a".repeat(64),
                        "s3://stove-builds/build.zip");
        var conflict = ProjectReviewRequestedEvent.of(id, changed);
        assertThatThrownBy(() -> receive.execute(ReviewSubmissionMapper.from(ReviewFixtures.envelope(conflict), conflict)))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM processed_event WHERE event_id=?",
                                Integer.class,
                                conflict.eventId()))
                .isZero();
        assertThat(read.snapshot(read.get(id))).isEqualTo(event.snapshot());
    }

    @Test
    void malformedEnvelopeDoesNotCreateAnInboxMarker() {
        var e = ReviewFixtures.event(id(), id());
        assertThatThrownBy(
                        () ->
                                receive.execute(ReviewSubmissionMapper.from(new EventEnvelope(
                                                e.eventId(),
                                                e.eventType(),
                                                "wrong",
                                                ReviewFixtures.json(e)), e)))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM processed_event WHERE event_id=?",
                                Integer.class,
                                e.eventId()))
                .isZero();
        assertThatThrownBy(() -> read.get(e.reviewRequestId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void decisionRetriesPublishOnceAndRetainTheFirstActor() {
        long id = id();
        var e = submit(id, id());
        var first = decide.execute(id, "first-reviewer", ReviewStatus.APPROVED, null);
        var retry = decide.execute(id, "second-reviewer", ReviewStatus.APPROVED, null);
        assertThat(retry.getActor()).isEqualTo(first.getActor());
        assertThat(retry.getDecidedAt()).isEqualTo(first.getDecidedAt());
        assertThatThrownBy(() -> decide.execute(id, "other", ReviewStatus.REJECTED, "Change"))
                .isInstanceOf(BusinessException.class);
        assertThat(outboxCount(e.partitionKey())).isEqualTo(1);
        var result =
                jdbc.queryForMap(
                        "SELECT aggregate_id,partition_key,payload FROM outbox_event WHERE"
                                + " aggregate_id=?",
                        e.partitionKey());
        assertThat(result.get("partition_key")).isEqualTo(e.partitionKey());
        assertThat(result.get("payload").toString()).contains("first-reviewer", "contentVersion");
    }

    @Test
    void outboxFailureRollsBackTheDecision() {
        long id = id();
        var e = submit(id, id());
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx ->
                                doThrow(new IllegalStateException("outbox unavailable"))
                                        .when(outbox)
                                        .record(eq("ProjectReview"), eq(e.partitionKey()), any()));
        assertThatThrownBy(() -> decide.execute(id, "reviewer", ReviewStatus.APPROVED, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(read.get(id).getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        assertThat(read.get(id).getActor()).isNull();
        assertThat(outboxCount(e.partitionKey())).isZero();
    }

    @Test
    void simultaneousOppositeDecisionsCommitExactlyOneResult() throws Exception {
        long id = id();
        var e = submit(id, id());
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> decision(start, id, ReviewStatus.APPROVED, null));
            var b = pool.submit(() -> decision(start, id, ReviewStatus.REJECTED, "Fix"));
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(outboxCount(e.partitionKey())).isEqualTo(1);
            assertThat(read.get(id).getStatus()).isNotEqualTo(ReviewStatus.REQUESTED);
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean decision(CountDownLatch start, long id, ReviewStatus status, String feedback)
            throws Exception {
        start.await();
        try {
            decide.execute(id, "reviewer-" + status, status, feedback);
            return true;
        } catch (BusinessException conflict) {
            return false;
        }
    }

    @Test
    void simultaneousDuplicateIntakeCanRetryAfterTheFailedTransaction() throws Exception {
        var e = ReviewFixtures.event(id(), id());
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Void> task =
                () -> {
                    start.await();
                    for (int attempt = 0; ; attempt++) {
                        try {
                            receive.execute(ReviewSubmissionMapper.from(ReviewFixtures.envelope(e), e));
                            return null;
                        } catch (org.springframework.dao.DataAccessException conflict) {
                            if (attempt == 3) throw conflict;
                        }
                    }
                };
        try {
            var a = pool.submit(task);
            var b = pool.submit(task);
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM project_review WHERE review_request_id=?",
                                    Integer.class,
                                    e.reviewRequestId()))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM processed_event WHERE event_id=?",
                                    Integer.class,
                                    e.eventId()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void realKafkaDeliveryUsesTheNewGroupAndIgnoresOtherStudioEvents() throws Exception {
        var e = ReviewFixtures.event(id(), id());
        listeners.start();
        try {
            var record =
                    new ProducerRecord<String, String>(
                            Topics.STUDIO, e.partitionKey(), ReviewFixtures.json(e));
            record.headers()
                    .add(EventHeaders.EVENT_ID, e.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers()
                    .add(EventHeaders.EVENT_TYPE, e.eventType().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(10, TimeUnit.SECONDS);
            kafka.send(record).get(10, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .ignoreException(BusinessException.class)
                    .untilAsserted(
                            () ->
                                    assertThat(read.get(e.reviewRequestId()).getStatus())
                                            .isEqualTo(ReviewStatus.REQUESTED));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM processed_event WHERE event_id=? AND"
                                            + " consumer_group=?",
                                    Integer.class,
                                    e.eventId(),
                                    ReceiveProjectReview.CONSUMER_GROUP))
                    .isEqualTo(1);
        } finally {
            listeners.stop();
        }
    }

    private int outboxCount(String code) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", Integer.class, code);
    }
}
