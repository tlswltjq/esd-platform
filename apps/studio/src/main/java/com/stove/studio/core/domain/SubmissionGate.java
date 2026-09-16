package com.stove.studio.core.domain;

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

@Entity
@Getter
@Table(name = "submission_gate", uniqueConstraints =
        @UniqueConstraint(name = "uk_submission_gate_type", columnNames = {"submissionId", "reviewType"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionGate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long submissionId;
    @Column(nullable = false, length = 30) private String reviewType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private SubmissionGateStatus status;
    @Column(length = 30) private String reasonCode;
    @Column(length = 1000) private String feedback;
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

    public void changesRequested(String reasonCode, String feedback) {
        if (status == SubmissionGateStatus.PENDING) {
            status = SubmissionGateStatus.CHANGES_REQUESTED;
            this.reasonCode = reasonCode;
            this.feedback = feedback;
        }
    }
}
