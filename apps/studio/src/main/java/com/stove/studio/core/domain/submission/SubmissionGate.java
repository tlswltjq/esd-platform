package com.stove.studio.core.domain.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유형별 심사 결과를 반영해 출시 가능 여부를 판단하는 관문.
 */
@Entity
@Getter
@Table(name = "submission_gate", uniqueConstraints =
        @UniqueConstraint(name = "uk_submission_gate_type", columnNames = {"submissionId", "reviewType"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionGate {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** 심사할 자료와 빌드를 묶은 제출물 식별자. */
    @Column(nullable = false) private Long submissionId;
    /** 등급·상점·빌드 등 심사할 업무 유형. */
    @Column(nullable = false, length = 30) private String reviewType;
    /** 해당 심사 유형의 대기·승인·수정 요청 상태. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private SubmissionGateStatus status;
    /** 처리 결과 또는 요청 사유를 구분하는 코드. */
    @Column(length = 30) private String reasonCode;
    /** 창작자에게 전달할 수정 요청 내용. */
    @Column(length = 1000) private String feedback;
    /** 동시 수정 충돌을 감지하는 낙관적 잠금 버전. */
    @Version @Column(nullable = false) private long entityVersion;

    private SubmissionGate(Long submissionId, String reviewType) {
        this.submissionId = submissionId;
        this.reviewType = reviewType;
        this.status = SubmissionGateStatus.PENDING;
    }

    public static SubmissionGate pending(Long submissionId, String reviewType) {
        return new SubmissionGate(submissionId, reviewType);
    }

    public void approve() {
        if (status == SubmissionGateStatus.PENDING) status = SubmissionGateStatus.APPROVED;
    }

    public void reopen() {
        if (status == SubmissionGateStatus.CHANGES_REQUESTED) {
            status = SubmissionGateStatus.PENDING;
            reasonCode = null;
            feedback = null;
        }
    }

    public void changesRequested(String reasonCode, String feedback) {
        if (status == SubmissionGateStatus.PENDING) {
            status = SubmissionGateStatus.CHANGES_REQUESTED;
            this.reasonCode = reasonCode;
            this.feedback = feedback;
        }
    }
}
