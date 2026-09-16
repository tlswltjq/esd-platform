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
    private Long previousReleaseId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReleaseStatus status;
    private Instant publishAt;
    private Instant publishedAt;
    @Version @Column(nullable = false) private long entityVersion;

    private Release(Submission submission, Instant publishAt, Long previousReleaseId) {
        this.gameId = submission.getGameId();
        this.workspaceId = submission.getWorkspaceId();
        this.submissionId = submission.getId();
        this.buildId = submission.getBuildId();
        this.metadataRevisionId = submission.getMetadataRevisionId();
        this.pricingRevisionId = submission.getPricingRevisionId();
        this.ratingRevisionId = submission.getRatingRevisionId();
        this.previousReleaseId = previousReleaseId;
        this.channel = ReleaseChannel.LIVE;
        this.publishAt = publishAt;
        this.status = ReleaseStatus.SCHEDULED;
    }

    public static Release scheduled(Submission submission, Instant publishAt, Long previousReleaseId) {
        return new Release(submission, publishAt, previousReleaseId);
    }

    public void publish(Instant now) {
        if (status == ReleaseStatus.PUBLISHED) return;
        if (status != ReleaseStatus.SCHEDULED || (publishAt != null && publishAt.isAfter(now))) {
            throw new BusinessException(ErrorCode.CONFLICT, "아직 발행할 수 없는 릴리스입니다.");
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
}
