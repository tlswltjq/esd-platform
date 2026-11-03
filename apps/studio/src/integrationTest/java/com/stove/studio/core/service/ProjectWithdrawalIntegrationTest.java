package com.stove.studio.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.studio.StudioIntegrationTestBase;
import com.stove.studio.api.listener.ProjectWithdrawalResultMapper;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.StudioPage;
import com.stove.studio.core.domain.project.ProjectRequestCommand;
import com.stove.studio.core.domain.project.ProjectRequestCommandRepository;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.domain.project.StorePage;
import com.stove.studio.core.domain.project.SubmissionCommand;
import com.stove.studio.core.domain.project.WithdrawalCommand;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewResult;
import com.stove.studio.core.service.build.RegisterBuild;
import com.stove.studio.core.service.project.CreateProject;
import com.stove.studio.core.service.project.ReadProjects;
import com.stove.studio.core.service.project.RecordProjectWithdrawalResult;
import com.stove.studio.core.service.project.RequestProjectWithdrawal;
import com.stove.studio.core.service.project.UpdateStorePage;
import com.stove.studio.core.service.project.UploadThumbnail;
import com.stove.studio.core.service.release.RequestProjectRelease;
import com.stove.studio.core.service.review.RecordProjectReviewResult;
import com.stove.studio.core.service.review.RequestProjectReview;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProjectWithdrawalIntegrationTest extends StudioIntegrationTestBase {
    @Autowired CreateProject create;
    @Autowired ReadProjects read;
    @Autowired UpdateStorePage update;
    @Autowired UploadThumbnail thumbnail;
    @Autowired RegisterBuild build;
    @Autowired RequestProjectReview review;
    @Autowired RecordProjectReviewResult decision;
    @Autowired RequestProjectRelease release;
    @Autowired RequestProjectWithdrawal withdraw;
    @Autowired RecordProjectWithdrawalResult resolution;
    @Autowired ProjectRequestCommandRepository commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean OutboxRecorder outbox;

    @Test
    void pendingOldVersionBlocksNewSubmissionButOldCommandRetriesRemainStable() throws Exception {
        Project project = ready();
        SubmissionCommand original = new SubmissionCommand("first", project.getContentVersion());
        ProjectReviewRequest first = review.execute(project.getId(), project.getOwnerSubject(), original);
        update.execute(project.getId(), project.getOwnerSubject(), project.getContentVersion(),
                new StorePage("Changed", "Summary", project.getThumbnailUrl(), null, "Changed description", 1000));
        Project current = read.requireOwned(project.getId(), project.getOwnerSubject());
        assertThat(read.reviewRequest(current)).isNull();
        assertThat(read.activeReviewRequest(current).getId()).isEqualTo(first.getId());
        assertThatThrownBy(() -> review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("new", current.getContentVersion()))).isInstanceOf(BusinessException.class);
        assertThat(review.execute(project.getId(), project.getOwnerSubject(), original).getId()).isEqualTo(first.getId());
        assertThatThrownBy(() -> review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("first", current.getContentVersion()))).isInstanceOf(BusinessException.class);
        assertThat(read.reviewRequests(project.getId(), project.getOwnerSubject(), new StudioPage(0, 100)).getContent()).hasSize(1);
    }

    @Test
    void withdrawalWaitsForFinalResultThenSameContentCanBeSubmittedWithoutRevivingOldCommands() throws Exception {
        Project project = ready();
        SubmissionCommand original = new SubmissionCommand("first", project.getContentVersion());
        ProjectReviewRequest first = review.execute(project.getId(), project.getOwnerSubject(), original);
        WithdrawalCommand cancel = new WithdrawalCommand("cancel", null);
        ProjectRequestCommand receipt = withdraw.review(project.getId(), first.getId(), project.getOwnerSubject(), cancel);
        assertThat(receipt.getState()).isEqualTo("PENDING");
        assertThat(withdraw.receipts(project.getId(), first.getId(), project.getOwnerSubject(),
                ProjectRequestKind.WITHDRAW_REVIEW, 0, 20)).extracting(ProjectRequestCommand::getId).containsExactly(receipt.getId());
        assertThat(withdraw.review(project.getId(), first.getId(), project.getOwnerSubject(), cancel).getId()).isEqualTo(receipt.getId());
        assertThatThrownBy(() -> review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("again", project.getContentVersion()))).isInstanceOf(BusinessException.class);
        ProjectWithdrawalResult fact = fact(receipt, project, "WITHDRAWN", project.getOwnerSubject(), first.getRequestEventId());
        recordReview(fact);
        ProjectReviewRequest next = review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("again", project.getContentVersion()));
        assertThat(next.getId()).isNotEqualTo(first.getId());
        assertThat(next.getSnapshot()).isEqualTo(first.getSnapshot());
        assertThat(review.execute(project.getId(), project.getOwnerSubject(), original).getStatus()).isEqualTo("WITHDRAWN");
        recordReview(fact);
        assertThat(read.activeReviewRequest(read.requireOwned(project.getId(), project.getOwnerSubject())).getId()).isEqualTo(next.getId());
        assertThat(withdraw.review(project.getId(), first.getId(), project.getOwnerSubject(), cancel).getState()).isEqualTo("WITHDRAWN");
        assertThat(count(project, "ProjectReviewRequested")).isEqualTo(2);
    }

    @Test
    void withdrawalDenialCanRepairTheMissingApprovalResultWithoutPublishingAutomatically() throws Exception {
        Project project = ready();
        ProjectReviewRequest request = review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("review", project.getContentVersion()));
        ProjectRequestCommand command = withdraw.review(project.getId(), request.getId(), project.getOwnerSubject(),
                new WithdrawalCommand("cancel", null));
        recordReview(fact(command, project, "APPROVED", "source-reviewer", request.getRequestEventId()));
        Project current = read.requireOwned(project.getId(), project.getOwnerSubject());
        assertThat(read.reviewRequest(current).approvedFor(current.getContentVersion())).isTrue();
        assertThat(read.reviewRequest(current).getActor()).isEqualTo("source-reviewer");
        assertThat(commands.findById(command.getId()).orElseThrow().getState()).isEqualTo("DENIED");
        assertThat(read.releaseRequests(project.getId(), project.getOwnerSubject(), new StudioPage(0, 100)).getContent()).isEmpty();
    }

    @Test
    void aResultWithTheWrongOriginOrReusedEnvelopeCannotFinalizeAnotherCommand() throws Exception {
        Project project = ready();
        ProjectReviewRequest first = review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("first", project.getContentVersion()));
        ProjectRequestCommand command = withdraw.review(project.getId(), first.getId(), project.getOwnerSubject(),
                new WithdrawalCommand("cancel-first", null));
        ProjectReviewWithdrawalResolvedEvent wrong = ProjectReviewWithdrawalResolvedEvent.of(
                fact(command, project, "WITHDRAWN", project.getOwnerSubject(), "wrong-origin"));
        assertThatThrownBy(() -> recordReviewEvent(wrong)).isInstanceOf(BusinessException.class);
        assertThat(commands.findById(command.getId()).orElseThrow().getState()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=?", Integer.class, wrong.eventId())).isZero();
        ProjectReviewWithdrawalResolvedEvent correct = ProjectReviewWithdrawalResolvedEvent.of(
                fact(command, project, "WITHDRAWN", project.getOwnerSubject(), first.getRequestEventId()));
        recordReviewEvent(correct);
        ProjectReviewRequest second = review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("second", project.getContentVersion()));
        ProjectRequestCommand next = withdraw.review(project.getId(), second.getId(), project.getOwnerSubject(),
                new WithdrawalCommand("cancel-second", null));
        ProjectReviewWithdrawalResolvedEvent reused = new ProjectReviewWithdrawalResolvedEvent(correct.eventId(), correct.occurredAt(),
                fact(next, project, "WITHDRAWN", project.getOwnerSubject(), second.getRequestEventId()));
        assertThatThrownBy(() -> recordReviewEvent(reused)).isInstanceOf(BusinessException.class);
        assertThat(commands.findById(next.getId()).orElseThrow().getState()).isEqualTo("PENDING");
        assertThat(read.activeReviewRequest(read.requireOwned(project.getId(), project.getOwnerSubject())).getId()).isEqualTo(second.getId());
    }

    @Test
    void releaseWithdrawalKeepsApprovalAndNewIntentCanReuseIt() throws Exception {
        Project project = ready();
        ProjectReviewRequest approved = review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("review", project.getContentVersion()));
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", approved.getId(),
                project.getId(), project.getContentVersion(), true, null, "reviewer"));
        SubmissionCommand firstIntent = new SubmissionCommand("release", project.getContentVersion());
        ProjectReleaseRequest first = release.execute(project.getId(), project.getOwnerSubject(), firstIntent);
        ProjectRequestCommand command = withdraw.release(project.getId(), first.getId(), project.getOwnerSubject(),
                new WithdrawalCommand("cancel", "schedule changed"));
        ProjectWithdrawalResult fact = new ProjectWithdrawalResult(command.getId(), first.getId(), project.getId(),
                project.getContentVersion(), project.getProductCode(), "WITHDRAWN", project.getOwnerSubject(), "schedule changed",
                Instant.now().truncatedTo(ChronoUnit.MICROS), first.getRequestEventId());
        ProjectReleaseWithdrawalResolvedEvent event = ProjectReleaseWithdrawalResolvedEvent.of(fact);
        resolution.execute(ProjectWithdrawalResultMapper.from(new EventEnvelope(event.eventId(), event.eventType(),
                event.partitionKey(), mapper.writeValueAsString(event)), event));
        assertThat(read.reviewRequest(read.requireOwned(project.getId(), project.getOwnerSubject())).approvedFor(project.getContentVersion())).isTrue();
        ProjectReleaseRequest next = release.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("new-release", project.getContentVersion()));
        assertThat(next.getId()).isNotEqualTo(first.getId());
        assertThat(next.getReviewRequestId()).isEqualTo(approved.getId());
        assertThat(release.execute(project.getId(), project.getOwnerSubject(), firstIntent).getStatus()).isEqualTo("WITHDRAWN");
        assertThat(count(project, "ProjectReviewRequested")).isEqualTo(1);
        assertThat(count(project, "ProjectReleaseRequested")).isEqualTo(2);
    }

    @Test
    void twoConcurrentNewCommandsShareOneActiveReviewAndOneRequestEvent() throws Exception {
        Project project = ready();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectReviewRequest> first = pool.submit(() -> {
                start.await(); return review.execute(project.getId(), project.getOwnerSubject(), new SubmissionCommand("one", project.getContentVersion()));
            });
            Future<ProjectReviewRequest> second = pool.submit(() -> {
                start.await(); return review.execute(project.getId(), project.getOwnerSubject(), new SubmissionCommand("two", project.getContentVersion()));
            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).getId()).isEqualTo(second.get(20, TimeUnit.SECONDS).getId());
            assertThat(read.reviewRequests(project.getId(), project.getOwnerSubject(), new StudioPage(0, 100)).getContent()).hasSize(1);
            assertThat(count(project, "ProjectReviewRequested")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void anEarlierReleaseBlocksNewVersionsUntilItsWithdrawalIsFinalWithoutBlockingReview() throws Exception {
        Project project = ready();
        ProjectReviewRequest firstReview = review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("review-one", project.getContentVersion()));
        approve(project, firstReview);
        SubmissionCommand firstIntent = new SubmissionCommand("release-one", project.getContentVersion());
        ProjectReleaseRequest first = release.execute(project.getId(), project.getOwnerSubject(), firstIntent);
        update.execute(project.getId(), project.getOwnerSubject(), project.getContentVersion(),
                new StorePage("Update", "Summary", project.getThumbnailUrl(), null, "Updated content", 2000));
        Project current = read.requireOwned(project.getId(), project.getOwnerSubject());
        ProjectReviewRequest nextReview = review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("review-two", current.getContentVersion()));
        approve(current, nextReview);
        assertThat(read.activeReleaseRequest(current).getId()).isEqualTo(first.getId());
        SubmissionCommand nextIntent = new SubmissionCommand("release-two", current.getContentVersion());
        assertThatThrownBy(() -> release.execute(project.getId(), project.getOwnerSubject(), nextIntent))
                .isInstanceOf(BusinessException.class);
        assertThat(release.execute(project.getId(), project.getOwnerSubject(), firstIntent).getId()).isEqualTo(first.getId());
        ProjectRequestCommand cancellation = withdraw.release(project.getId(), first.getId(), project.getOwnerSubject(),
                new WithdrawalCommand("cancel-one", null));
        assertThatThrownBy(() -> release.execute(project.getId(), project.getOwnerSubject(), nextIntent))
                .isInstanceOf(BusinessException.class);
        ProjectReleaseWithdrawalResolvedEvent event = ProjectReleaseWithdrawalResolvedEvent.of(
                fact(cancellation, current, "WITHDRAWN", project.getOwnerSubject(), first.getRequestEventId()));
        resolution.execute(ProjectWithdrawalResultMapper.from(new EventEnvelope(event.eventId(), event.eventType(),
                event.partitionKey(), mapper.writeValueAsString(event)), event));
        ProjectReleaseRequest next = release.execute(project.getId(), project.getOwnerSubject(), nextIntent);
        assertThat(next.getId()).isNotEqualTo(first.getId());
        assertThat(next.getReviewRequestId()).isEqualTo(nextReview.getId());
        assertThat(read.activeReleaseRequest(current).getId()).isEqualTo(next.getId());
        resolution.execute(ProjectWithdrawalResultMapper.from(new EventEnvelope(event.eventId(), event.eventType(),
                event.partitionKey(), mapper.writeValueAsString(event)), event));
        assertThat(read.releaseRequest(read.requireOwned(project.getId(), project.getOwnerSubject())).getId()).isEqualTo(next.getId());
        assertThat(count(project, "ProjectReleaseRequested")).isEqualTo(2);
    }

    @Test
    void concurrentReleaseCommandsCreateOneRequestAndOneEvent() throws Exception {
        Project project = ready();
        ProjectReviewRequest approved = review.execute(project.getId(), project.getOwnerSubject(),
                new SubmissionCommand("review", project.getContentVersion()));
        approve(project, approved);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProjectReleaseRequest> first = pool.submit(() -> {
                start.await(); return release.execute(project.getId(), project.getOwnerSubject(), new SubmissionCommand("one", project.getContentVersion()));
            });
            Future<ProjectReleaseRequest> second = pool.submit(() -> {
                start.await(); return release.execute(project.getId(), project.getOwnerSubject(), new SubmissionCommand("two", project.getContentVersion()));
            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).getId()).isEqualTo(second.get(20, TimeUnit.SECONDS).getId());
            assertThat(read.releaseRequests(project.getId(), project.getOwnerSubject(), new StudioPage(0, 100)).getContent()).hasSize(1);
            assertThat(count(project, "ProjectReleaseRequested")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    private void approve(Project project, ProjectReviewRequest request) {
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", request.getId(),
                project.getId(), request.getContentVersion(), true, null, "reviewer"));
    }

    @Test
    void outboxFailureDoesNotLeaveAWithdrawalCommandAndWrongOwnerCannotSubmitIt() throws Exception {
        Project project = ready();
        ProjectReviewRequest request = review.execute(project.getId(), project.getOwnerSubject(), new SubmissionCommand("review", project.getContentVersion()));
        assertThatThrownBy(() -> withdraw.review(project.getId(), request.getId(), "another-owner", new WithdrawalCommand("forged", null)))
                .isInstanceOf(BusinessException.class);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                doThrow(new IllegalStateException("outbox unavailable")).when(outbox)
                        .record(eq("Project"), eq(project.getProductCode()), any(ProjectReviewWithdrawalRequestedEvent.class)));
        assertThatThrownBy(() -> withdraw.review(project.getId(), request.getId(), project.getOwnerSubject(), new WithdrawalCommand("cancel", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(commands.findByProjectIdAndKindAndRequestKey(project.getId(), ProjectRequestKind.WITHDRAW_REVIEW, "cancel")).isEmpty();
        assertThat(read.activeReviewRequest(read.requireOwned(project.getId(), project.getOwnerSubject())).getStatus()).isEqualTo("REQUESTED");
    }

    private Project ready() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        Long id = create.execute(owner, "W-" + UUID.randomUUID(), "Game").getId();
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", image);
        thumbnail.execute(id, owner, new ByteArrayInputStream(image.toByteArray()));
        Project project = read.requireOwned(id, owner);
        update.execute(id, owner, project.getContentVersion(), new StorePage("Game", "Summary", project.getThumbnailUrl(), null, "Content", 1000));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("game.txt")); zip.write(new byte[] {1, 2, 3}); zip.closeEntry();
        }
        build.execute(id, owner, "1", "game.zip", new ByteArrayInputStream(bytes.toByteArray()));
        return read.requireOwned(id, owner);
    }

    private ProjectWithdrawalResult fact(ProjectRequestCommand command, Project project, String state, String actor, String origin) {
        return new ProjectWithdrawalResult(command.getId(), command.getTargetRequestId(), project.getId(), command.getContentVersion(),
                project.getProductCode(), state, actor, null, Instant.now().truncatedTo(ChronoUnit.MICROS), origin);
    }
    private void recordReview(ProjectWithdrawalResult fact) throws Exception {
        recordReviewEvent(ProjectReviewWithdrawalResolvedEvent.of(fact));
    }
    private void recordReviewEvent(ProjectReviewWithdrawalResolvedEvent event) throws Exception {
        resolution.execute(ProjectWithdrawalResultMapper.from(new EventEnvelope(event.eventId(), event.eventType(),
                event.partitionKey(), mapper.writeValueAsString(event)), event));
    }
    private int count(Project project, String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type=?",
                Integer.class, project.getProductCode(), eventType);
    }
}
