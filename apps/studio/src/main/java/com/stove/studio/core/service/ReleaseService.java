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
import com.stove.studio.core.domain.ReleaseChannel;
import com.stove.studio.core.domain.ReleaseChangeType;
import com.stove.studio.core.domain.ReleaseRepository;
import com.stove.studio.core.domain.ReleaseStatus;
import com.stove.studio.core.domain.StorePageRevision;
import com.stove.studio.core.domain.StorePageRevisionRepository;
import com.stove.studio.core.domain.Submission;
import com.stove.studio.core.domain.SubmissionRepository;
import com.stove.studio.core.domain.SubmissionStatus;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneId;
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
    private final ReleaseSmokeTestService smokeTestService;

    public Release create(Long submissionId, Long workspaceId, Instant publishAt) {
        return create(submissionId, workspaceId, publishAt, "system:legacy");
    }

    public Release create(Long submissionId, Long workspaceId, Instant publishAt, String actor) {
        return create(submissionId, workspaceId, publishAt, "UTC", ReleaseChannel.LIVE, null, actor);
    }

    public Release create(Long submissionId, Long workspaceId, Instant publishAt, String timeZone,
                          ReleaseChannel channel, ReleaseChangeType requestedChangeType, String actor) {
        Submission submission = requireOwnedSubmission(submissionId, workspaceId);
        if (submission.getStatus() != SubmissionStatus.READY_FOR_RELEASE) {
            throw new BusinessException(ErrorCode.CONFLICT, "출시 준비가 완료된 제출물만 릴리스할 수 있습니다.");
        }
        ReleaseChannel effectiveChannel = channel == null ? ReleaseChannel.LIVE : channel;
        String effectiveTimeZone = requireTimeZone(timeZone);
        Release previousRelease = releaseRepository.findTopByGameIdAndChannelAndStatusOrderByPublishedAtDesc(
                        submission.getGameId(), effectiveChannel, ReleaseStatus.PUBLISHED).orElse(null);
        ReleaseChangeType actualChangeType = classify(submission, previousRelease);
        if (requestedChangeType != null && requestedChangeType != actualChangeType) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "revision 변경 범위와 릴리스 변경 유형이 일치하지 않습니다. expected=" + actualChangeType);
        }
        Instant effectivePublishAt = publishAt == null ? Instant.now() : publishAt;
        Release release = releaseRepository.save(Release.scheduled(submission, effectiveChannel,
                actualChangeType, effectivePublishAt, effectiveTimeZone,
                previousRelease == null ? null : previousRelease.getId()));
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

    public Release promote(Long sourceReleaseId, Long workspaceId, ReleaseChannel targetChannel,
                           Instant publishAt, String timeZone, String actor) {
        Release source = requireOwnedRelease(sourceReleaseId, workspaceId);
        if (source.getStatus() != ReleaseStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "발행된 릴리스만 다음 채널로 승격할 수 있습니다.");
        }
        if (targetChannel == null || targetChannel.ordinal() != source.getChannel().ordinal() + 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "채널은 dev→test→stage→live 순서로 승격해야 합니다.");
        }
        Submission submission = requireOwnedSubmission(source.getSubmissionId(), workspaceId);
        if (submission.getStatus() != SubmissionStatus.READY_FOR_RELEASE) {
            throw new BusinessException(ErrorCode.CONFLICT, "출시 준비 상태의 제출물만 승격할 수 있습니다.");
        }
        return create(submission.getId(), workspaceId, publishAt, timeZone, targetChannel,
                classify(submission, releaseRepository.findTopByGameIdAndChannelAndStatusOrderByPublishedAtDesc(
                        submission.getGameId(), targetChannel, ReleaseStatus.PUBLISHED).orElse(null)), actor);
    }

    public Release rollback(Long targetReleaseId, Long workspaceId) {
        return rollback(targetReleaseId, workspaceId, "system:legacy");
    }

    public Release rollback(Long targetReleaseId, Long workspaceId, String actor) {
        Release target = requireOwnedRelease(targetReleaseId, workspaceId);
        if (target.getStatus() != ReleaseStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "발행된 릴리스로만 rollback할 수 있습니다.");
        }
        Release current = releaseRepository.findTopByGameIdAndChannelAndStatusOrderByPublishedAtDesc(
                        target.getGameId(), target.getChannel(), ReleaseStatus.PUBLISHED)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "현재 릴리스가 없습니다."));
        Submission submission = submissionRepository.findById(target.getSubmissionId()).orElseThrow();
        Release rollback = releaseRepository.save(Release.scheduled(submission, target.getChannel(),
                ReleaseChangeType.ROLLBACK, Instant.now(), target.getPublishTimeZone(), current.getId()));
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

    public Release reschedule(Long releaseId, Long workspaceId, Instant publishAt,
                              String timeZone, String actor) {
        Release release = requireOwnedRelease(releaseId, workspaceId);
        release.reschedule(publishAt, requireTimeZone(timeZone));
        auditLogService.record(actor, "RELEASE_RESCHEDULED", "Release", releaseId,
                "publishAt=" + publishAt + ",timeZone=" + release.getPublishTimeZone());
        return release;
    }

    private void publish(Release release, Submission submission, GameProject project, String actor) {
        GameBuild build = buildRepository.findById(release.getBuildId()).orElseThrow();
        try {
            smokeTestService.verify(build);
            release.smokePassed(Instant.now());
        } catch (RuntimeException exception) {
            release.smokeFailed(Instant.now(), safeMessage(exception));
            auditLogService.record(actor, "RELEASE_SMOKE_TEST_FAILED", "Release", release.getId(),
                    safeMessage(exception));
            return;
        }
        release.publish(Instant.now());
        if (release.getChannel() != ReleaseChannel.LIVE) {
            auditLogService.record(actor, "RELEASE_PROMOTED", "Release", release.getId(),
                    "channel=" + release.getChannel() + ",buildId=" + build.getId());
            return;
        }
        submission.released();
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

    private ReleaseChangeType classify(Submission submission, Release previous) {
        if (previous == null) return ReleaseChangeType.INITIAL;
        boolean material = !previous.getMetadataRevisionId().equals(submission.getMetadataRevisionId())
                || !previous.getPricingRevisionId().equals(submission.getPricingRevisionId())
                || !previous.getRatingRevisionId().equals(submission.getRatingRevisionId());
        return material ? ReleaseChangeType.MATERIAL_CHANGE : ReleaseChangeType.NORMAL_PATCH;
    }

    private String requireTimeZone(String value) {
        String timeZone = value == null || value.isBlank() ? "UTC" : value;
        try {
            ZoneId.of(timeZone);
            return timeZone;
        } catch (DateTimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "유효한 IANA 시간대가 필요합니다.");
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 500));
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
