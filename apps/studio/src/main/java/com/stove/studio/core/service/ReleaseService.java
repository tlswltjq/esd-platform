package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ReleasePublishedEvent;
import com.stove.common.event.payload.ReleaseRolledBackEvent;
import com.stove.common.event.payload.ReleaseScheduledEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.PricingRevision;
import com.stove.studio.core.domain.PricingRevisionRepository;
import com.stove.studio.core.domain.Release;
import com.stove.studio.core.domain.ReleaseRepository;
import com.stove.studio.core.domain.ReleaseStatus;
import com.stove.studio.core.domain.StorePageRevision;
import com.stove.studio.core.domain.StorePageRevisionRepository;
import com.stove.studio.core.domain.Submission;
import com.stove.studio.core.domain.SubmissionRepository;
import com.stove.studio.core.domain.SubmissionStatus;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final SubmissionRepository submissionRepository;
    private final GameProjectService projectService;
    private final GameBuildRepository buildRepository;
    private final StorePageRevisionRepository storeRepository;
    private final PricingRevisionRepository pricingRepository;
    private final OutboxRecorder outboxRecorder;
    private final AuditLogService auditLogService;

    public Release create(Long submissionId, Long workspaceId, Instant publishAt) {
        return create(submissionId, workspaceId, publishAt, "system:legacy");
    }

    public Release create(Long submissionId, Long workspaceId, Instant publishAt, String actor) {
        Submission submission = requireOwnedSubmission(submissionId, workspaceId);
        if (submission.getStatus() != SubmissionStatus.READY_FOR_RELEASE) {
            throw new BusinessException(ErrorCode.CONFLICT, "출시 준비가 완료된 제출물만 릴리스할 수 있습니다.");
        }
        Long previous = releaseRepository.findTopByGameIdAndStatusOrderByPublishedAtDesc(
                        submission.getGameId(), ReleaseStatus.PUBLISHED)
                .map(Release::getId).orElse(null);
        Instant effectivePublishAt = publishAt == null ? Instant.now() : publishAt;
        Release release = releaseRepository.save(Release.scheduled(submission, effectivePublishAt, previous));
        GameProject project = projectService.requireById(submission.getGameId());
        if (effectivePublishAt.isAfter(Instant.now())) {
            outboxRecorder.record("Release", project.getProductCode(),
                    ReleaseScheduledEvent.of(release.getId(), submissionId,
                            project.getProductCode(), effectivePublishAt));
            auditLogService.record(actor, "RELEASE_SCHEDULED", "Release", release.getId(),
                    "submissionId=" + submissionId + ",publishAt=" + effectivePublishAt);
        } else {
            publish(release, submission, project, actor);
        }
        return release;
    }

    public Release rollback(Long targetReleaseId, Long workspaceId) {
        return rollback(targetReleaseId, workspaceId, "system:legacy");
    }

    public Release rollback(Long targetReleaseId, Long workspaceId, String actor) {
        Release target = requireOwnedRelease(targetReleaseId, workspaceId);
        if (target.getStatus() != ReleaseStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "발행된 릴리스로만 rollback할 수 있습니다.");
        }
        Release current = releaseRepository.findTopByGameIdAndStatusOrderByPublishedAtDesc(
                        target.getGameId(), ReleaseStatus.PUBLISHED)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "현재 릴리스가 없습니다."));
        Submission submission = submissionRepository.findById(target.getSubmissionId()).orElseThrow();
        Release rollback = releaseRepository.save(Release.scheduled(submission, Instant.now(), current.getId()));
        GameProject project = projectService.requireById(target.getGameId());
        publish(rollback, submission, project, actor);
        outboxRecorder.record("Release", project.getProductCode(), ReleaseRolledBackEvent.of(
                rollback.getId(), current.getId(), rollback.getBuildId(), project.getProductCode()));
        auditLogService.record(actor, "RELEASE_ROLLED_BACK", "Release", rollback.getId(),
                "restoredReleaseId=" + targetReleaseId + ",previousReleaseId=" + current.getId());
        return rollback;
    }

    public int publishDue() {
        List<Release> due = releaseRepository.findTop100ByStatusAndPublishAtLessThanEqualOrderByPublishAtAsc(
                ReleaseStatus.SCHEDULED, Instant.now());
        due.forEach(release -> {
            Submission submission = submissionRepository.findById(release.getSubmissionId()).orElseThrow();
            publish(release, submission, projectService.requireById(release.getGameId()), "system:scheduler");
        });
        return due.size();
    }

    public void cancel(Long releaseId, Long workspaceId) {
        cancel(releaseId, workspaceId, "system:legacy");
    }

    public void cancel(Long releaseId, Long workspaceId, String actor) {
        requireOwnedRelease(releaseId, workspaceId).cancel();
        auditLogService.record(actor, "RELEASE_CANCELLED", "Release", releaseId, null);
    }

    private void publish(Release release, Submission submission, GameProject project, String actor) {
        release.publish(Instant.now());
        submission.released();
        GameBuild build = buildRepository.findById(release.getBuildId()).orElseThrow();
        StorePageRevision metadata = storeRepository.findById(release.getMetadataRevisionId()).orElseThrow();
        PricingRevision pricing = pricingRepository.findById(release.getPricingRevisionId()).orElseThrow();
        outboxRecorder.record("Release", project.getProductCode(), ReleasePublishedEvent.of(
                release.getId(), release.getPreviousReleaseId(), submission.getId(), project.getId(),
                project.getProductCode(), project.getSellerId(), build.getId(), metadata.getId(), pricing.getId(),
                release.getRatingRevisionId(), metadata.getTitle(), metadata.getShortDescription(),
                pricing.getPrice(), pricing.getCurrency(), submission.getRatingCode(), build.getVersion(),
                build.getFileSize(), build.getActualChecksum(), build.getStoragePath()));
        auditLogService.record(actor, "RELEASE_PUBLISHED", "Release", release.getId(),
                "buildId=" + build.getId() + ",submissionId=" + submission.getId());
    }

    private Submission requireOwnedSubmission(Long id, Long workspaceId) {
        return submissionRepository.findById(id)
                .filter(value -> value.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "submissionId=" + id));
    }

    private Release requireOwnedRelease(Long id, Long workspaceId) {
        return releaseRepository.findById(id)
                .filter(value -> value.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "releaseId=" + id));
    }
}
