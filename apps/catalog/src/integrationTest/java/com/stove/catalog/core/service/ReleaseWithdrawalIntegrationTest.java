package com.stove.catalog.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.CatalogFixtures;
import com.stove.catalog.CatalogIntegrationTestBase;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.api.listener.ReleaseWithdrawalMapper;
import com.stove.catalog.core.domain.CatalogPublicationJob;
import com.stove.catalog.core.domain.CatalogPublicationJobRepository;
import com.stove.catalog.core.domain.CatalogReleaseRepository;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.ListingPreference;
import com.stove.catalog.core.domain.ReleaseWithdrawalInput;
import com.stove.catalog.core.port.PublicationTracing;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReleaseCompletedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReleaseWithdrawalIntegrationTest extends CatalogIntegrationTestBase {
    @Autowired ReceiveProjectRelease receive;
    @Autowired ReceiveReleaseWithdrawal withdraw;
    @Autowired PublishQueuedReleases worker;
    @Autowired CatalogPublicationJobRepository jobs;
    @Autowired CatalogReleaseRepository releases;
    @Autowired CatalogProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoSpyBean PublicationTracing tracing;

    @Test
    void intakeCommitsBeforePublicationAndWorkerRestoresTheStoredTrace() {
        ProjectReleaseRequestedEvent original = original();
        String trace = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
        doReturn(trace).when(tracing).capture();
        accept(original);
        accept(original);
        assertThat(products.findByProductCode(original.partitionKey())).isEmpty();
        assertThat(releases.findById(original.releaseRequestId())).isEmpty();
        assertThat(job(original).getState()).isEqualTo("PENDING");
        assertThat(job(original).getTraceParent()).isEqualTo(trace);
        assertThat(inbox(original.eventId())).isEqualTo(1);
        worker.execute();
        assertThat(job(original).getState()).isEqualTo("COMPLETED");
        assertThat(products.findByProductCode(original.partitionKey())).isPresent();
        verify(tracing).call(eq(trace), eq(original.releaseRequestId().toString()), any());
    }

    @Test
    void normalIntakeThenWithdrawalCanWinBeforeWorkerAndSameApprovalCanBeResubmitted() throws Exception {
        ProjectReleaseRequestedEvent original = original();
        accept(original);
        ProjectReleaseWithdrawalRequestedEvent command = command(original, null);
        withdraw.execute(input(command));
        assertThat(resolution(original).result().status()).isEqualTo("WITHDRAWN");
        worker.execute();
        accept(original);
        assertThat(products.findByProductCode(original.partitionKey())).isEmpty();
        assertThat(releases.findById(original.releaseRequestId()).orElseThrow().getStatus()).isEqualTo("WITHDRAWN");
        assertThat(count(original, "CatalogProductPublished")).isZero();
        ProjectReleaseRequestedEvent retry = ProjectReleaseRequestedEvent.of(id(), original.reviewRequestId(), original.snapshot());
        accept(retry);
        worker.execute();
        assertThat(products.findByProductCode(original.partitionKey()).orElseThrow().getCurrentReleaseRequestId())
                .isEqualTo(retry.releaseRequestId());
        assertThat(releases.findById(retry.releaseRequestId()).orElseThrow().getReviewRequestId())
                .isEqualTo(original.reviewRequestId());
        assertThat(count(original, "CatalogProductPublished")).isEqualTo(1);
    }

    @Test
    void aWithdrawnUpdateKeepsTheEarlierPublicBuildAndIndependentControls() {
        ProjectReleaseRequestedEvent first = original();
        accept(first);
        worker.execute();
        long currentId = products.findByProductCode(first.partitionKey()).orElseThrow().getCurrentReleaseRequestId();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            CatalogProduct product = products.lockByProjectId(first.snapshot().projectId()).orElseThrow();
            product.changeSaleStatus("STOPPED", product.getCatalogVersion(), Instant.now().truncatedTo(ChronoUnit.MICROS));
            product.changeListingPreference(ListingPreference.UNLISTED, product.getCatalogVersion(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        });
        ProjectReleaseRequestedEvent update = CatalogFixtures.event(first.snapshot().projectId(), 2);
        accept(update);
        withdraw.execute(input(command(update, "fix description")));
        worker.execute();
        assertThat(products.findByProductCode(first.partitionKey()).orElseThrow().getCurrentReleaseRequestId())
                .isEqualTo(currentId);
        assertThat(releases.findById(currentId).orElseThrow().getSnapshot()).contains(first.snapshot().title());
        assertThat(products.findByProductCode(first.partitionKey()).orElseThrow().getSellerSaleStatus()).isEqualTo("STOPPED");
        assertThat(products.findByProductCode(first.partitionKey()).orElseThrow().getListingPreference()).isEqualTo(ListingPreference.UNLISTED);
        assertThat(count(first, "CatalogProductPublished")).isEqualTo(1);
    }

    @Test
    void withdrawalBeforeOriginalWaitsForTheOriginalAndCannotBeReversedByReplay() throws Exception {
        ProjectReleaseRequestedEvent original = original();
        ProjectReleaseWithdrawalRequestedEvent command = command(original, null);
        withdraw.execute(input(command));
        assertThat(releases.findById(original.releaseRequestId())).isEmpty();
        accept(original);
        accept(original);
        worker.execute();
        assertThat(resolution(original).result().status()).isEqualTo("WITHDRAWN");
        assertThat(products.findByProductCode(original.partitionKey())).isEmpty();
        assertThat(count(original, "ProjectReleaseWithdrawalResolved")).isEqualTo(1);
    }

    @Test
    void publicationBeforeWithdrawalIsPreserved() throws Exception {
        ProjectReleaseRequestedEvent original = original();
        accept(original);
        worker.execute();
        withdraw.execute(input(command(original, null)));
        assertThat(resolution(original).result().status()).isEqualTo("COMPLETED");
        assertThat(releases.findById(original.releaseRequestId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(products.findByProductCode(original.partitionKey()).orElseThrow().getCurrentReleaseRequestId())
                .isEqualTo(original.releaseRequestId());
    }

    @Test
    void publicationFailureRollsBackPublicWritesAndRetriesTheSameAcceptedJob() {
        ProjectReleaseRequestedEvent original = original();
        accept(original);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                doThrow(new IllegalStateException("result outbox unavailable")).when(outbox)
                        .record(eq("CatalogProduct"), eq(original.partitionKey()), any(ProjectReleaseCompletedEvent.class)));
        worker.execute();
        CatalogPublicationJob failed = job(original);
        assertThat(failed.getState()).isEqualTo("PENDING");
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).isNotBlank();
        assertThat(failed.getNextAttemptAt()).isNotNull();
        assertThat(releases.findById(original.releaseRequestId())).isEmpty();
        assertThat(products.findByProductCode(original.partitionKey())).isEmpty();
        assertThat(inbox(original.eventId())).isEqualTo(1);
        assertThat(count(original, "CatalogProductPublished")).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                doCallRealMethod().when(outbox)
                        .record(eq("CatalogProduct"), eq(original.partitionKey()), any(ProjectReleaseCompletedEvent.class)));
        jdbc.update("UPDATE catalog_publication_job SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE request_id=?", original.releaseRequestId());
        worker.execute();
        assertThat(job(original).getState()).isEqualTo("COMPLETED");
        assertThat(job(original).getSourceEventId()).isEqualTo(original.eventId());
        assertThat(count(original, "CatalogProductPublished")).isEqualTo(1);
    }

    @Test
    void wrongOwnerCannotWithdrawAnAcceptedJobAndConcurrentPublicationHasOneFinalResult() throws Exception {
        ProjectReleaseRequestedEvent original = original();
        accept(original);
        ProjectReleaseWithdrawalRequestedEvent forged = ProjectReleaseWithdrawalRequestedEvent.of(
                new ProjectWithdrawalRequest(id(), original.releaseRequestId(), original.snapshot().projectId(),
                        1, original.partitionKey(), "another-owner", null));
        assertThatThrownBy(() -> withdraw.execute(input(forged))).isInstanceOf(BusinessException.class);
        assertThat(inbox(forged.eventId())).isZero();
        ProjectReleaseWithdrawalRequestedEvent command = command(original, null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> publication = pool.submit(() -> { start.await(); return worker.execute(); });
            Future<Void> cancellation = pool.submit(() -> { start.await(); withdraw.execute(input(command)); return null; });
            start.countDown();
            publication.get(20, TimeUnit.SECONDS);
            cancellation.get(20, TimeUnit.SECONDS);
            String state = releases.findById(original.releaseRequestId()).orElseThrow().getStatus();
            assertThat(state).isIn("COMPLETED", "WITHDRAWN");
            assertThat(resolution(original).result().status()).isEqualTo(state);
            assertThat(products.findByProductCode(original.partitionKey()).isPresent()).isEqualTo("COMPLETED".equals(state));
        } finally { pool.shutdownNow(); }
        worker.execute();
    }

    private long id() { return UUID.randomUUID().getMostSignificantBits() >>> 1; }
    private ProjectReleaseRequestedEvent original() { return CatalogFixtures.event(id(), 1); }
    private void accept(ProjectReleaseRequestedEvent event) {
        receive.execute(ReleaseSubmissionMapper.from(CatalogFixtures.envelope(event), event));
    }
    private CatalogPublicationJob job(ProjectReleaseRequestedEvent event) {
        return jobs.findById(event.releaseRequestId()).orElseThrow();
    }
    private ProjectReleaseWithdrawalRequestedEvent command(ProjectReleaseRequestedEvent event, String reason) {
        return ProjectReleaseWithdrawalRequestedEvent.of(new ProjectWithdrawalRequest(id(), event.releaseRequestId(),
                event.snapshot().projectId(), event.snapshot().contentVersion(), event.partitionKey(), "creator", reason));
    }
    private ReleaseWithdrawalInput input(ProjectReleaseWithdrawalRequestedEvent event) {
        return ReleaseWithdrawalMapper.from(new EventEnvelope(event.eventId(), event.eventType(),
                event.partitionKey(), CatalogFixtures.json(event)), event);
    }
    private int inbox(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=?", Integer.class, eventId);
    }
    private int count(ProjectReleaseRequestedEvent event, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type=?",
                Integer.class, event.partitionKey(), type);
    }
    private ProjectReleaseWithdrawalResolvedEvent resolution(ProjectReleaseRequestedEvent event) throws Exception {
        String json = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id=? AND event_type=?",
                String.class, event.partitionKey(), "ProjectReleaseWithdrawalResolved");
        return mapper.readValue(json, ProjectReleaseWithdrawalResolvedEvent.class);
    }
}
