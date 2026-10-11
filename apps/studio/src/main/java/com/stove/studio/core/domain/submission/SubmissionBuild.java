package com.stove.studio.core.domain.submission;

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
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** 심사할 자료와 빌드를 묶은 제출물 식별자. */
    @Column(nullable = false) private Long submissionId;
    /** 배포 파일을 나타내는 빌드 식별자. */
    @Column(nullable = false) private Long buildId;

    private SubmissionBuild(Long submissionId, Long buildId) {
        this.submissionId = submissionId;
        this.buildId = buildId;
    }

    public static SubmissionBuild of(Long submissionId, Long buildId) {
        return new SubmissionBuild(submissionId, buildId);
    }
}
