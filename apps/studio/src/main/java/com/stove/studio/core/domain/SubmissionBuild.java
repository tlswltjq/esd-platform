package com.stove.studio.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable build set reviewed together as one submission. */
@Entity
@Getter
@Table(name = "submission_build")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionBuild {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long submissionId;
    @Column(nullable = false) private Long buildId;

    private SubmissionBuild(Long submissionId, Long buildId) {
        this.submissionId = submissionId;
        this.buildId = buildId;
    }

    public static SubmissionBuild of(Long submissionId, Long buildId) {
        return new SubmissionBuild(submissionId, buildId);
    }
}
