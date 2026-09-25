package com.stove.studio.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "game_release")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Release extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false) private Long workspaceId;
    @Column(nullable = false) private Long submissionId;
    @Column(nullable = false) private Long buildId;
    @Column(nullable = false) private Long metadataRevisionId;
    @Column(nullable = false) private Long pricingRevisionId;
    @Column(nullable = false) private Long ratingRevisionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReleaseChannel channel;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReleaseChangeType changeType;
    private Long previousReleaseId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReleaseStatus status;
    private Instant publishAt;
    private Instant publishedAt;
    @Column(nullable = false, length = 50) private String publishTimeZone;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SmokeTestStatus smokeTestStatus;
    private Instant smokeTestedAt;
    @Column(length = 500) private String smokeTestFailure;
    @Version @Column(nullable = false) private long entityVersion;

    private Release(Submission submission, ReleaseChannel channel, ReleaseChangeType changeType,
                    Instant publishAt, String publishTimeZone, Long previousReleaseId) {
        this.gameId = submission.getGameId();
        this.workspaceId = submission.getWorkspaceId();
        this.submissionId = submission.getId();
        this.buildId = submission.getBuildId();
        this.metadataRevisionId = submission.getMetadataRevisionId();
        this.pricingRevisionId = submission.getPricingRevisionId();
        this.ratingRevisionId = submission.getRatingRevisionId();
        this.previousReleaseId = previousReleaseId;
        this.channel = channel;
        this.changeType = changeType;
        this.publishAt = publishAt;
        this.publishTimeZone = publishTimeZone;
        this.status = ReleaseStatus.SCHEDULED;
        this.smokeTestStatus = SmokeTestStatus.PENDING;
    }

    public static Release scheduled(Submission submission, Instant publishAt, Long previousReleaseId) {
        return scheduled(submission, ReleaseChannel.LIVE, ReleaseChangeType.INITIAL,
                publishAt, "UTC", previousReleaseId);
    }

    public static Release scheduled(Submission submission, ReleaseChannel channel,
                                    ReleaseChangeType changeType, Instant publishAt,
                                    String publishTimeZone, Long previousReleaseId) {
        return new Release(submission, channel, changeType, publishAt, publishTimeZone, previousReleaseId);
    }

    public void smokePassed(Instant now) {
        requireScheduled();
        smokeTestStatus = SmokeTestStatus.PASSED;
        smokeTestedAt = now;
        smokeTestFailure = null;
    }

    public void smokeFailed(Instant now, String failure) {
        requireScheduled();
        smokeTestStatus = SmokeTestStatus.FAILED;
        smokeTestedAt = now;
        smokeTestFailure = failure;
        status = ReleaseStatus.SMOKE_TEST_FAILED;
    }

    public void publish(Instant now) {
        if (status == ReleaseStatus.PUBLISHED) return;
        if (status != ReleaseStatus.SCHEDULED || (publishAt != null && publishAt.isAfter(now))) {
            throw new BusinessException(ErrorCode.CONFLICT, "아직 발행할 수 없는 릴리스입니다.");
        }
        if (smokeTestStatus != SmokeTestStatus.PASSED) {
            throw new BusinessException(ErrorCode.CONFLICT, "출시 직전 smoke test를 통과해야 합니다.");
        }
        status = ReleaseStatus.PUBLISHED;
        publishedAt = now;
    }

    public void cancel() {
        if (status != ReleaseStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.CONFLICT, "예약된 릴리스만 취소할 수 있습니다.");
        }
        status = ReleaseStatus.CANCELLED;
    }

    public void reschedule(Instant newPublishAt, String timeZone) {
        requireScheduled();
        if (newPublishAt == null || !newPublishAt.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "재예약 시각은 현재보다 이후여야 합니다.");
        }
        publishAt = newPublishAt;
        publishTimeZone = timeZone;
        smokeTestStatus = SmokeTestStatus.PENDING;
        smokeTestedAt = null;
        smokeTestFailure = null;
    }

    private void requireScheduled() {
        if (status != ReleaseStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.CONFLICT, "예약된 릴리스만 변경할 수 있습니다.");
        }
    }
}
