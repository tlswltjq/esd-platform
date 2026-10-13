package com.stove.studio.core.domain.release;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.common.jpa.BaseTimeEntity;
import com.stove.studio.core.domain.submission.Submission;
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

/**
 * 승인된 자료와 빌드를 지정 채널에 예약·공개하는 릴리스.
 */
@Entity
@Getter
@Table(name = "game_release")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Release extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 프로젝트와 권한을 소유한 작업 공간 식별자. */
    @Column(nullable = false) private Long workspaceId;
    /** 심사할 자료와 빌드를 묶은 제출물 식별자. */
    @Column(nullable = false) private Long submissionId;
    /** 배포 파일을 나타내는 빌드 식별자. */
    @Column(nullable = false) private Long buildId;
    /** 제출 또는 공개에 사용할 상점 소개 판본 식별자. */
    @Column(nullable = false) private Long metadataRevisionId;
    /** 제출 또는 공개에 사용할 가격 판본 식별자. */
    @Column(nullable = false) private Long pricingRevisionId;
    /** 제출 또는 공개에 사용할 등급 자료 판본 식별자. */
    @Column(nullable = false) private Long ratingRevisionId;
    /** 릴리스를 공개하거나 설치를 허용할 배포 채널. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReleaseChannel channel;
    /** 최초 출시·패치·중대 변경·롤백의 구분. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReleaseChangeType changeType;
    /** 변경 또는 롤백의 기준이 되는 이전 릴리스 식별자. */
    private Long previousReleaseId;
    /** 예약한 릴리스의 공개·취소·실행 점검 상태. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReleaseStatus status;
    /** 릴리스를 공개하도록 예약한 시각. */
    private Instant publishAt;
    /** 공개 처리를 완료한 시각. */
    private Instant publishedAt;
    /** 공개 예약 시각을 해석하고 표시할 시간대. */
    @Column(nullable = false, length = 50) private String publishTimeZone;
    /** 출시 전 기본 실행 점검의 상태. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SmokeTestStatus smokeTestStatus;
    /** 출시 전 기본 실행 점검을 수행한 시각. */
    private Instant smokeTestedAt;
    /** 출시 전 기본 실행 점검의 실패 사유. */
    @Column(length = 500) private String smokeTestFailure;
    /** 동시 수정 충돌을 감지하는 낙관적 잠금 버전. */
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
