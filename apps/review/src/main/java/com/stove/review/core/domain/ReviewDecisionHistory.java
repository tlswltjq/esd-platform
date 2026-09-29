package com.stove.review.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "review_decision_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewDecisionHistory extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long reviewCaseId;
    @Column(nullable = false, length = 40) private String action;
    @Column(nullable = false, length = 100) private String actor;
    @Column(length = 30) private String fromStatus;
    @Column(nullable = false, length = 30) private String toStatus;
    @Column(length = 2000) private String details;

    private ReviewDecisionHistory(Long reviewCaseId, String action, String actor,
                                  ReviewCaseStatus from, ReviewCaseStatus to, String details) {
        this.reviewCaseId = reviewCaseId;
        this.action = action;
        this.actor = actor;
        this.fromStatus = from == null ? null : from.name();
        this.toStatus = to.name();
        this.details = details;
    }

    public static ReviewDecisionHistory record(Long reviewCaseId, String action, String actor,
                                               ReviewCaseStatus from, ReviewCaseStatus to,
                                               String details) {
        return new ReviewDecisionHistory(reviewCaseId, action, actor, from, to, details);
    }
}
