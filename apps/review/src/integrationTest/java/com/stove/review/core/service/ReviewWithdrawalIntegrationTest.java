package com.stove.review.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.review.ReviewFixtures;
import com.stove.review.ReviewIntegrationTestBase;
import com.stove.review.api.listener.ReviewSubmissionMapper;
import com.stove.review.api.listener.ReviewWithdrawalMapper;
import com.stove.review.core.domain.ReviewStatus;
import com.stove.review.core.domain.ReviewWithdrawalInput;
import com.stove.review.core.domain.ReviewWithdrawalRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReviewWithdrawalIntegrationTest extends ReviewIntegrationTestBase {
    @Autowired ReceiveProjectReview receive;
    @Autowired ReceiveReviewWithdrawal withdraw;
    @Autowired DecideProjectReview decide;
    @Autowired ReadProjectReviews read;
    @Autowired ReviewWithdrawalRepository withdrawals;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean OutboxRecorder outbox;

    @Test
    void withdrawalBeforeOriginalIsDurableAndOriginalReplayCannotReopenIt() throws Exception {
        ProjectReviewRequestedEvent original = original();
        ProjectReviewWithdrawalRequestedEvent command = command(original, null);
        withdraw.execute(input(command));
        withdraw.execute(input(command));
        assertThat(withdrawals.findById(command.request().withdrawalId()).orElseThrow().isResolved()).isFalse();
        assertThat(count("project_review", "review_request_id", original.reviewRequestId())).isZero();
        receive(original);
        receive(original);
        assertThat(read.get(original.reviewRequestId()).getStatus()).isEqualTo(ReviewStatus.WITHDRAWN);
        assertThat(withdrawals.findById(command.request().withdrawalId()).orElseThrow().isResolved()).isTrue();
        assertThat(resolution(original).result().status()).isEqualTo("WITHDRAWN");
        assertThat(resultCount(original)).isEqualTo(1);
        assertThatThrownBy(() -> decide.execute(original.reviewRequestId(), "reviewer", ReviewStatus.APPROVED, null))
                .isInstanceOf(BusinessException.class);
        assertThat(read.snapshot(read.get(original.reviewRequestId()))).isEqualTo(original.snapshot());
    }

    @Test
    void approvalBeforeWithdrawalReturnsTheFirstFinalFact() throws Exception {
        ProjectReviewRequestedEvent original = original();
        receive(original);
        decide.execute(original.reviewRequestId(), "first-reviewer", ReviewStatus.APPROVED, null);
        ProjectReviewWithdrawalRequestedEvent command = command(original, "plans changed");
        withdraw.execute(input(command));
        ProjectReviewWithdrawalResolvedEvent result = resolution(original);
        assertThat(result.result().status()).isEqualTo("APPROVED");
        assertThat(result.result().actor()).isEqualTo("first-reviewer");
        assertThat(result.result().finalizedAt()).isEqualTo(read.get(original.reviewRequestId()).getDecidedAt());
        assertThat(read.get(original.reviewRequestId()).getWithdrawnAt()).isNull();
    }

    @Test
    void wrongOwnerAndChangedBusinessCommandDoNotMarkInboxOrOverwriteState() {
        ProjectReviewRequestedEvent original = original();
        receive(original);
        ProjectWithdrawalRequest bad = new ProjectWithdrawalRequest(id(), original.reviewRequestId(),
                original.snapshot().projectId(), 1, original.partitionKey(), "another-creator", null);
        ProjectReviewWithdrawalRequestedEvent forged = ProjectReviewWithdrawalRequestedEvent.of(bad);
        assertThatThrownBy(() -> withdraw.execute(input(forged))).isInstanceOf(BusinessException.class);
        assertThat(count("processed_event", "event_id", forged.eventId())).isZero();
        assertThat(withdrawals.findById(bad.withdrawalId())).isEmpty();
        assertThat(read.get(original.reviewRequestId()).getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        ProjectReviewWithdrawalRequestedEvent valid = command(original, "original reason");
        withdraw.execute(input(valid));
        ProjectWithdrawalRequest changed = new ProjectWithdrawalRequest(valid.request().withdrawalId(),
                original.reviewRequestId(), original.snapshot().projectId(), 1, original.partitionKey(), "creator", "changed");
        ProjectReviewWithdrawalRequestedEvent conflicting = ProjectReviewWithdrawalRequestedEvent.of(changed);
        assertThatThrownBy(() -> withdraw.execute(input(conflicting))).isInstanceOf(BusinessException.class);
        assertThat(count("processed_event", "event_id", conflicting.eventId())).isZero();
        assertThat(read.get(original.reviewRequestId()).getWithdrawalReason()).isEqualTo("original reason");
    }

    @Test
    void outboxFailureRollsBackWithdrawalInboxAndItsReceipt() {
        ProjectReviewRequestedEvent original = original();
        receive(original);
        ProjectReviewWithdrawalRequestedEvent command = command(original, null);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                doThrow(new IllegalStateException("outbox unavailable")).when(outbox)
                        .record(eq("ProjectReview"), eq(original.partitionKey()), any(ProjectReviewWithdrawalResolvedEvent.class)));
        assertThatThrownBy(() -> withdraw.execute(input(command))).isInstanceOf(IllegalStateException.class);
        assertThat(read.get(original.reviewRequestId()).getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        assertThat(withdrawals.findById(command.request().withdrawalId())).isEmpty();
        assertThat(count("processed_event", "event_id", command.eventId())).isZero();
        assertThat(resultCount(original)).isZero();
    }

    @Test
    void simultaneousApprovalAndWithdrawalAgreeOnOneFinalState() throws Exception {
        ProjectReviewRequestedEvent original = original();
        receive(original);
        ProjectReviewWithdrawalRequestedEvent command = command(original, null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> approval = pool.submit(() -> {
                start.await();
                try {
                    decide.execute(original.reviewRequestId(), "reviewer", ReviewStatus.APPROVED, null);
                    return true;
                } catch (BusinessException conflict) {
                    return false;
                }
            });
            Future<Void> withdrawal = pool.submit(() -> {
                start.await();
                withdraw.execute(input(command));
                return null;
            });
            start.countDown();
            boolean approved = approval.get(20, TimeUnit.SECONDS);
            withdrawal.get(20, TimeUnit.SECONDS);
            String expected = approved ? "APPROVED" : "WITHDRAWN";
            assertThat(read.get(original.reviewRequestId()).getStatus().name()).isEqualTo(expected);
            assertThat(resolution(original).result().status()).isEqualTo(expected);
            assertThat(resultCount(original)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void realKafkaCanDeliverWithdrawalBeforeOriginalAndRepeatedOriginalRemainsWithdrawn() throws Exception {
        ProjectReviewRequestedEvent original = original();
        ProjectReviewWithdrawalRequestedEvent command = command(original, null);
        listeners.start();
        try {
            send(command);
            send(original);
            send(original);
            Awaitility.await().atMost(Duration.ofSeconds(25)).ignoreException(BusinessException.class)
                    .untilAsserted(() -> assertThat(read.get(original.reviewRequestId()).getStatus())
                            .isEqualTo(ReviewStatus.WITHDRAWN));
            assertThat(resultCount(original)).isEqualTo(1);
        } finally {
            listeners.stop();
        }
    }

    private long id() { return UUID.randomUUID().getMostSignificantBits() >>> 1; }
    private ProjectReviewRequestedEvent original() { return ReviewFixtures.event(id(), id()); }
    private void receive(ProjectReviewRequestedEvent event) {
        receive.execute(ReviewSubmissionMapper.from(ReviewFixtures.envelope(event), event));
    }
    private ProjectReviewWithdrawalRequestedEvent command(ProjectReviewRequestedEvent original, String reason) {
        return ProjectReviewWithdrawalRequestedEvent.of(new ProjectWithdrawalRequest(id(), original.reviewRequestId(),
                original.snapshot().projectId(), original.snapshot().contentVersion(), original.partitionKey(), "creator", reason));
    }
    private ReviewWithdrawalInput input(ProjectReviewWithdrawalRequestedEvent event) {
        return ReviewWithdrawalMapper.from(new EventEnvelope(event.eventId(), event.eventType(),
                event.partitionKey(), ReviewFixtures.json(event)), event);
    }
    private int count(String table, String column, Object value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, value);
    }
    private int resultCount(ProjectReviewRequestedEvent original) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type=?",
                Integer.class, original.partitionKey(), "ProjectReviewWithdrawalResolved");
    }
    private ProjectReviewWithdrawalResolvedEvent resolution(ProjectReviewRequestedEvent original) throws Exception {
        String json = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id=? AND event_type=?",
                String.class, original.partitionKey(), "ProjectReviewWithdrawalResolved");
        return mapper.readValue(json, ProjectReviewWithdrawalResolvedEvent.class);
    }
    private void send(DomainEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.STUDIO, event.partitionKey(), ReviewFixtures.json(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }
}
