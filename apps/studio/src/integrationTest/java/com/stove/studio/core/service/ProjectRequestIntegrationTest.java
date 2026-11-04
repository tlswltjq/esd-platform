package com.stove.studio.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.studio.StudioIntegrationTestBase;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.domain.build.BuildRepository;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.StorePage;
import com.stove.studio.core.domain.project.StudioPage;
import com.stove.studio.core.domain.project.SubmissionCommand;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseResult;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewResult;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.build.RegisterBuild;
import com.stove.studio.core.service.project.CreateProject;
import com.stove.studio.core.service.project.ReadProjects;
import com.stove.studio.core.service.project.UpdateStorePage;
import com.stove.studio.core.service.project.UploadThumbnail;
import com.stove.studio.core.service.release.RecordProjectReleaseResult;
import com.stove.studio.core.service.release.RequestProjectRelease;
import com.stove.studio.core.service.review.RecordProjectReviewResult;
import com.stove.studio.core.service.review.RequestProjectReview;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class ProjectRequestIntegrationTest extends StudioIntegrationTestBase {
    @Autowired CreateProject create;
    @Autowired ReadProjects read;
    @Autowired UpdateStorePage update;
    @Autowired UploadThumbnail thumbnail;
    @Autowired RegisterBuild upload;
    @Autowired RequestProjectReview review;
    @Autowired RecordProjectReviewResult decision;
    @Autowired RequestProjectRelease release;
    @Autowired RecordProjectReleaseResult result;

    @Autowired BuildRepository builds;
    @Autowired BuildStorage storage;
    @Autowired JdbcTemplate jdbc;

    @Test
    void metadataAndImmutableRequestsAreIndependentOfExternalReviewAndReleaseExecution()
            throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        int workspaceCount = jdbc.queryForObject("SELECT COUNT(*) FROM workspace", Integer.class);
        Long id = create.execute(owner, "P-" + UUID.randomUUID(), "Game").getId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace", Integer.class))
                .isEqualTo(workspaceCount);
        thumbnail.execute(id, owner, new ByteArrayInputStream(png()));
        Project p = read.requireOwned(id, owner);
        update.execute(
                id,
                owner,
                p.getContentVersion(),
                new StorePage("Game", "Summary", p.getThumbnailUrl(), null, "Content", 1000, List.of("ACTION")));
        Build build = upload.execute(id, owner, "1", "game.zip", new ByteArrayInputStream(zip()));
        assertThat(storage.size(build.getStoragePath())).isEqualTo(build.getFileSize());
        ProjectReviewRequest request = review.execute(id, owner, submission(id, owner));
        assertThat(read.snapshot(request).ownerSubject()).isEqualTo(owner);
        assertThat(review.execute(id, owner, submission(id, owner)).getId()).isEqualTo(request.getId());
        assertThatThrownBy(() -> release.execute(id, owner, submission(id, owner))).isInstanceOf(BusinessException.class);
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", request.getId(), id, request.getContentVersion(), true, null, "external-reviewer"));
        ProjectReleaseRequest first = release.execute(id, owner, submission(id, owner));
        assertThat(release.execute(id, owner, submission(id, owner)).getId()).isEqualTo(first.getId());
        result.execute(new ProjectReleaseResult(UUID.randomUUID().toString(), "ProjectReleaseCompleted", first.getId(), id, first.getContentVersion(), true, null, first.getRequestEventId()));
        assertThat(read.releaseRequests(id, owner, new StudioPage(0, 100)).getContent().get(0).getStatus()).isEqualTo("COMPLETED");
        p = read.requireOwned(id, owner);
        update.execute(
                id,
                owner,
                p.getContentVersion(),
                new StorePage("Updated", "Summary", p.getThumbnailUrl(), null, "Updated content", 2000, List.of("ACTION")));
        Build nextBuild = upload.execute(id, owner, "2", "game.zip", new ByteArrayInputStream(zip()));
        assertThat(read.snapshot(request).title()).isEqualTo("Game");
        assertThat(read.reviewRequest(read.requireOwned(id, owner))).isNull();
        assertThatThrownBy(() -> release.execute(id, owner, submission(id, owner))).isInstanceOf(BusinessException.class);
        ProjectReviewRequest next = review.execute(id, owner, submission(id, owner));
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", request.getId(), id, request.getContentVersion(), true, null, "old-result"));
        assertThat(read.reviewRequest(read.requireOwned(id, owner)).getStatus())
                .isEqualTo("REQUESTED");
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", next.getId(), id, next.getContentVersion(), true, null, "external-reviewer"));
        ProjectReleaseRequest failed = release.execute(id, owner, submission(id, owner));
        result.execute(new ProjectReleaseResult(UUID.randomUUID().toString(), "ProjectReleaseRejected", failed.getId(), id, failed.getContentVersion(), false, "External rejection", failed.getRequestEventId()));
        ProjectReleaseRequest retry = release.execute(id, owner, submission(id, owner));
        assertThat(retry.getId()).isNotEqualTo(failed.getId());
        result.execute(new ProjectReleaseResult(UUID.randomUUID().toString(), "ProjectReleaseCompleted", failed.getId(), id, failed.getContentVersion(), true, null, failed.getRequestEventId()));
        assertThat(read.releaseRequest(read.requireOwned(id, owner)).getStatus())
                .isEqualTo("REQUESTED");
        result.execute(new ProjectReleaseResult(UUID.randomUUID().toString(), "ProjectReleaseCompleted", retry.getId(), id, retry.getContentVersion(), true, null, retry.getRequestEventId()));
        List<ProjectReleaseRequest> history = read.releaseRequests(id, owner, new StudioPage(0, 100)).getContent();
        assertThat(history)
                .extracting(r -> r.getStatus())
                .containsExactly("COMPLETED", "REJECTED", "COMPLETED");
        assertThat(read.snapshot(next).buildId()).isEqualTo(nextBuild.getId());
        assertThat(storage.size(build.getStoragePath())).isEqualTo(build.getFileSize());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND"
                                    + " event_type='ProjectReleaseRequested'",
                                Integer.class,
                                p.getProductCode()))
                .isEqualTo(3);
    }

    @Test
    void metadataCanChangeWhileAReviewRequestIsPendingWithoutReusingItsLaterApproval()
            throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        Long id = create.execute(owner, "P-" + UUID.randomUUID(), "Game").getId();
        thumbnail.execute(id, owner, new ByteArrayInputStream(png()));
        Project p = read.requireOwned(id, owner);
        update.execute(
                id,
                owner,
                p.getContentVersion(),
                new StorePage("First", "Summary", p.getThumbnailUrl(), null, "Content", 1000, List.of("ACTION")));
        upload.execute(id, owner, "1", "game.zip", new ByteArrayInputStream(zip()));
        ProjectReviewRequest old = review.execute(id, owner, submission(id, owner));
        p = read.requireOwned(id, owner);
        update.execute(
                id,
                owner,
                p.getContentVersion(),
                new StorePage("Second", "Summary", p.getThumbnailUrl(), null, "Changed", 2000, List.of("ACTION")));
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", old.getId(), id, old.getContentVersion(), true, null, "external-reviewer"));
        Project current = read.requireOwned(id, owner);
        assertThat(current.getTitle()).isEqualTo("Second");
        assertThat(read.reviewRequest(current)).isNull();
        assertThatThrownBy(() -> release.execute(id, owner, submission(id, owner))).isInstanceOf(BusinessException.class);
        assertThat(read.snapshot(old).title()).isEqualTo("First");
        ProjectReviewRequest next = review.execute(id, owner, submission(id, owner));
        assertThat(next.getId()).isNotEqualTo(old.getId());
        assertThat(read.snapshot(next).title()).isEqualTo("Second");
    }

    @Test
    void genreOnlyChangeNeedsANewApprovalAndKeepsTheOriginalSubmission() throws Exception {
        String owner = "genre-owner-" + UUID.randomUUID();
        Long id = create.execute(owner, "GENRE-" + UUID.randomUUID(), "Game").getId();
        thumbnail.execute(id, owner, new ByteArrayInputStream(png()));
        upload.execute(id, owner, "1", "game.zip", new ByteArrayInputStream(zip()));
        Project project = read.requireOwned(id, owner);
        update.execute(id, owner, project.getContentVersion(), new StorePage("Game", "Summary", project.getThumbnailUrl(), null,
                "Content", 1000, List.of()));
        assertThatThrownBy(() -> review.execute(id, owner, submission(id, owner))).isInstanceOf(BusinessException.class);
        project = read.requireOwned(id, owner);
        update.execute(id, owner, project.getContentVersion(), new StorePage("Game", "Summary", project.getThumbnailUrl(), null,
                "Content", 1000, List.of("ACTION", "RPG")));
        ProjectReviewRequest first = review.execute(id, owner, submission(id, owner));
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", first.getId(), id,
                first.getContentVersion(), true, null, "reviewer"));
        project = read.requireOwned(id, owner);
        Long originalBuild = project.getCurrentBuildId();
        update.execute(id, owner, project.getContentVersion(), new StorePage("Game", "Summary", project.getThumbnailUrl(), null,
                "Content", 1000, List.of("ACTION", "PUZZLE")));
        assertThatThrownBy(() -> release.execute(id, owner, submission(id, owner))).isInstanceOf(BusinessException.class);
        assertThat(read.snapshot(first).genres()).containsExactly("ACTION", "RPG");
        ProjectReviewRequest second = review.execute(id, owner, submission(id, owner));
        assertThat(read.snapshot(second).buildId()).isEqualTo(originalBuild);
        assertThat(read.snapshot(second).genres()).containsExactly("ACTION", "PUZZLE");
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", second.getId(), id,
                second.getContentVersion(), true, null, "reviewer"));
        ProjectReleaseRequest accepted = release.execute(id, owner, submission(id, owner));
        assertThat(accepted.getReviewRequestId()).isEqualTo(second.getId());
        assertThat(jdbc.queryForList("SELECT genre_code FROM studio_project_genre WHERE project_id=? ORDER BY genre_code", String.class, id))
                .containsExactly("ACTION", "PUZZLE");
    }

    private SubmissionCommand submission(Long id, String owner) {
        return new SubmissionCommand(UUID.randomUUID().toString(), read.requireOwned(id, owner).getContentVersion());
    }

    @Test
    void anotherOwnerAndFailedFileCannotReplaceCurrentBuild() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        Long id = create.execute(owner, "P-" + UUID.randomUUID(), "Game").getId();
        assertThatThrownBy(() -> read.requireOwned(id, "other"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                upload.execute(
                                        id,
                                        owner,
                                        "bad",
                                        "bad.zip",
                                        new ByteArrayInputStream("bad".getBytes())))
                .isInstanceOf(BusinessException.class);
        assertThat(builds.findByProjectIdOrderByIdDesc(id, PageRequest.of(0, 100)).getContent()).isEmpty();
        assertThat(read.requireOwned(id, owner).getCurrentBuildId()).isNull();
        Build first = upload.execute(id, owner, "1", "game.zip", new ByteArrayInputStream(zip()));
        assertThatThrownBy(
                        () ->
                                upload.execute(
                                        id,
                                        owner,
                                        "1",
                                        "game.zip",
                                        new ByteArrayInputStream(zip())))
                .isInstanceOf(BusinessException.class);
        assertThat(builds.findByProjectIdOrderByIdDesc(id, PageRequest.of(0, 100)).getContent()).hasSize(1);
        assertThat(read.requireOwned(id, owner).getCurrentBuildId()).isEqualTo(first.getId());
    }

    private byte[] zip() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("game.bin"));
            zip.write(UUID.randomUUID().toString().getBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private byte[] png() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
