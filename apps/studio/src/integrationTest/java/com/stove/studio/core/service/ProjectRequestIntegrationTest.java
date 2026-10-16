package com.stove.studio.core.service;

import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseResult;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewResult;
import static org.assertj.core.api.Assertions.*;

import com.stove.common.web.error.BusinessException;
import com.stove.studio.StudioIntegrationTestBase;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.domain.build.BuildRepository;
import com.stove.studio.core.domain.project.*;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.build.RegisterBuild;
import com.stove.studio.core.service.project.*;
import com.stove.studio.core.service.release.*;
import com.stove.studio.core.service.review.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.*;

import javax.imageio.ImageIO;

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
                new StorePage("Game", "Summary", p.getThumbnailUrl(), null, "Content", 1000));
        Build build = upload.execute(id, owner, "1", "game.zip", new ByteArrayInputStream(zip()));
        assertThat(storage.size(build.getStoragePath())).isEqualTo(build.getFileSize());
        ProjectReviewRequest request = review.execute(id, owner);
        assertThat(read.snapshot(request).ownerSubject()).isEqualTo(owner);
        assertThat(review.execute(id, owner).getId()).isEqualTo(request.getId());
        assertThatThrownBy(() -> release.execute(id, owner)).isInstanceOf(BusinessException.class);
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", request.getId(), id, request.getContentVersion(), true, null, "external-reviewer"));
        ProjectReleaseRequest first = release.execute(id, owner);
        assertThat(release.execute(id, owner).getId()).isEqualTo(first.getId());
        result.execute(new ProjectReleaseResult(UUID.randomUUID().toString(), "ProjectReleaseCompleted", first.getId(), id, first.getContentVersion(), true, null, first.getRequestEventId()));
        assertThat(read.releaseRequests(id, owner).get(0).getStatus()).isEqualTo("COMPLETED");
        p = read.requireOwned(id, owner);
        update.execute(
                id,
                owner,
                p.getContentVersion(),
                new StorePage(
                        "Updated", "Summary", p.getThumbnailUrl(), null, "Updated content", 2000));
        Build nextBuild = upload.execute(id, owner, "2", "game.zip", new ByteArrayInputStream(zip()));
        assertThat(read.snapshot(request).title()).isEqualTo("Game");
        assertThat(read.reviewRequest(read.requireOwned(id, owner))).isNull();
        assertThatThrownBy(() -> release.execute(id, owner)).isInstanceOf(BusinessException.class);
        ProjectReviewRequest next = review.execute(id, owner);
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", request.getId(), id, request.getContentVersion(), true, null, "old-result"));
        assertThat(read.reviewRequest(read.requireOwned(id, owner)).getStatus())
                .isEqualTo("REQUESTED");
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", next.getId(), id, next.getContentVersion(), true, null, "external-reviewer"));
        ProjectReleaseRequest failed = release.execute(id, owner);
        result.execute(new ProjectReleaseResult(UUID.randomUUID().toString(), "ProjectReleaseRejected", failed.getId(), id, failed.getContentVersion(), false, "External rejection", failed.getRequestEventId()));
        ProjectReleaseRequest retry = release.execute(id, owner);
        assertThat(retry.getId()).isNotEqualTo(failed.getId());
        result.execute(new ProjectReleaseResult(UUID.randomUUID().toString(), "ProjectReleaseCompleted", failed.getId(), id, failed.getContentVersion(), true, null, failed.getRequestEventId()));
        assertThat(read.releaseRequest(read.requireOwned(id, owner)).getStatus())
                .isEqualTo("REQUESTED");
        result.execute(new ProjectReleaseResult(UUID.randomUUID().toString(), "ProjectReleaseCompleted", retry.getId(), id, retry.getContentVersion(), true, null, retry.getRequestEventId()));
        List<ProjectReleaseRequest> history = read.releaseRequests(id, owner);
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
                new StorePage("First", "Summary", p.getThumbnailUrl(), null, "Content", 1000));
        upload.execute(id, owner, "1", "game.zip", new ByteArrayInputStream(zip()));
        ProjectReviewRequest old = review.execute(id, owner);
        p = read.requireOwned(id, owner);
        update.execute(
                id,
                owner,
                p.getContentVersion(),
                new StorePage("Second", "Summary", p.getThumbnailUrl(), null, "Changed", 2000));
        decision.execute(new ProjectReviewResult(UUID.randomUUID().toString(), "ProjectReviewApproved", old.getId(), id, old.getContentVersion(), true, null, "external-reviewer"));
        Project current = read.requireOwned(id, owner);
        assertThat(current.getTitle()).isEqualTo("Second");
        assertThat(read.reviewRequest(current)).isNull();
        assertThatThrownBy(() -> release.execute(id, owner)).isInstanceOf(BusinessException.class);
        assertThat(read.snapshot(old).title()).isEqualTo("First");
        ProjectReviewRequest next = review.execute(id, owner);
        assertThat(next.getId()).isNotEqualTo(old.getId());
        assertThat(read.snapshot(next).title()).isEqualTo("Second");
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
        assertThat(builds.findByProjectIdOrderByIdDesc(id)).isEmpty();
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
        assertThat(builds.findByProjectIdOrderByIdDesc(id)).hasSize(1);
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
