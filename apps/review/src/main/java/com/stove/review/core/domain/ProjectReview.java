package com.stove.review.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;
import com.stove.common.web.error.*;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;
import java.util.Objects;

/** One immutable Studio submission and its final human decision. */
@Entity
@Getter
@Table(name = "project_review")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectReview extends BaseTimeEntity {
    @Id private Long reviewRequestId;

    @Column(nullable = false, updatable = false, length = 64)
    private String requestEventId;

    @Column(nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, updatable = false, length = 50)
    private String productCode;

    @Column(nullable = false, updatable = false, length = 100)
    private String ownerSubject;

    @Column(nullable = false, updatable = false)
    private long contentVersion;

    @Lob
    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String snapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    @Column(length = 2000)
    private String feedback;

    @Column(length = 100)
    private String actor;

    private Instant decidedAt;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    @Version private Long entityVersion;

    public static ProjectReview requested(ProjectReviewRequestedEvent event, String snapshot) {
        var r = new ProjectReview();
        var s = event.snapshot();
        r.reviewRequestId = event.reviewRequestId();
        r.requestEventId = event.eventId();
        r.projectId = s.projectId();
        r.productCode = s.productCode();
        r.ownerSubject = s.ownerSubject();
        r.contentVersion = s.contentVersion();
        r.snapshot = snapshot;
        r.status = ReviewStatus.REQUESTED;
        r.requestedAt = event.occurredAt();
        return r;
    }

    /** Returns false for an identical retry without replacing the original actor/time. */
    public boolean decide(ReviewStatus decision, String actor, String feedback) {
        if (decision == null
                || decision == ReviewStatus.REQUESTED
                || actor == null
                || actor.isBlank()
                || actor.length() > 100
                || (decision == ReviewStatus.APPROVED && feedback != null)
                || (decision == ReviewStatus.REJECTED
                        && (feedback == null || feedback.isBlank() || feedback.length() > 2000)))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (status != ReviewStatus.REQUESTED) {
            if (status == decision && Objects.equals(this.feedback, feedback)) return false;
            throw new BusinessException(ErrorCode.CONFLICT, "확정된 심사 판정은 변경할 수 없습니다.");
        }
        this.status = decision;
        this.actor = actor;
        this.feedback = feedback;
        this.decidedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return true;
    }
}
