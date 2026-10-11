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

/**
 * 심사 사건의 상태 변경과 판단 내용을 보존하는 이력.
 */
@Entity
@Getter
@Table(name = "review_decision_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewDecisionHistory extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** 판단 이력을 남길 심사 사건 식별자. */
    @Column(nullable = false) private Long reviewCaseId;
    /** 감사 기록에 남길 업무 행동명. */
    @Column(nullable = false, length = 40) private String action;
    /** 업무를 수행한 사용자 또는 시스템. */
    @Column(nullable = false, length = 100) private String actor;
    /** 변경 직전의 심사 상태. */
    @Column(length = 30) private String fromStatus;
    /** 업무 처리 후의 심사 상태. */
    @Column(nullable = false, length = 30) private String toStatus;
    /** 감사 또는 상태 변경의 상세 내용. */
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
