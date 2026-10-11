package com.stove.studio.core.domain.submission;

import com.stove.common.jpa.BaseTimeEntity;
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
 * 심사 대상 자료 판본과 빌드, 출시 승인 결과를 묶은 제출물.
 */
@Entity
@Getter
@Table(name = "submission", uniqueConstraints =
        @UniqueConstraint(name = "uk_submission_sequence", columnNames = {"gameId", "sequenceNo"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Submission extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 프로젝트와 권한을 소유한 작업 공간 식별자. */
    @Column(nullable = false) private Long workspaceId;
    /** 같은 프로젝트 내 제출 차수. */
    @Column(nullable = false) private int sequenceNo;
    /** 제출 또는 공개에 사용할 상점 소개 판본 식별자. */
    @Column(nullable = false) private Long metadataRevisionId;
    /** 제출 또는 공개에 사용할 가격 판본 식별자. */
    @Column(nullable = false) private Long pricingRevisionId;
    /** 제출 또는 공개에 사용할 등급 자료 판본 식별자. */
    @Column(nullable = false) private Long ratingRevisionId;
    /** 배포 파일을 나타내는 빌드 식별자. */
    @Column(nullable = false) private Long buildId;
    /** 자료 제출부터 심사 보완·출시 준비·공개까지의 진행 상태. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private SubmissionStatus status;
    /** 동시 수정 충돌을 감지하는 낙관적 잠금 버전. */
    @Version @Column(nullable = false) private long entityVersion;
    /** 확정된 이용 등급 코드. */
    @Column(length = 10) private String ratingCode;
    /** 승인된 등급분류 증명 번호. */
    @Column(length = 100) private String ratingCertificationNumber;
    /** 승인된 등급분류 결과의 발급 기관. */
    @Column(length = 100) private String ratingIssuer;
    /** 등급분류 결과를 발급한 시각. */
    private java.time.Instant ratingIssuedAt;
    /** 등급분류 결과가 적용되는 국가 코드. */
    @Column(length = 2) private String ratingCountry;
    /** 자체등급분류 또는 외부 등급분류 경로. */
    @Column(length = 30) private String ratingPath;
    /** 등급 판정에 적용한 정책 버전. */
    @Column(length = 30) private String ratingPolicyVersion;
    /** 등급분류 기관의 외부 접수 번호. */
    @Column(length = 100) private String ratingExternalApplicationNumber;
    /** 등급분류 결과를 증명하는 외부 URL. */
    @Column(length = 500) private String ratingExternalEvidenceUrl;

    private Submission(Long gameId, Long workspaceId, int sequenceNo, Long metadataRevisionId,
                       Long pricingRevisionId, Long ratingRevisionId, Long buildId) {
        this.gameId = gameId;
        this.workspaceId = workspaceId;
        this.sequenceNo = sequenceNo;
        this.metadataRevisionId = metadataRevisionId;
        this.pricingRevisionId = pricingRevisionId;
        this.ratingRevisionId = ratingRevisionId;
        this.buildId = buildId;
        this.status = SubmissionStatus.SUBMITTED;
    }

    public static Submission create(Long gameId, Long workspaceId, int sequenceNo,
                                    Long metadataRevisionId, Long pricingRevisionId,
                                    Long ratingRevisionId, Long buildId) {
        return new Submission(gameId, workspaceId, sequenceNo, metadataRevisionId,
                pricingRevisionId, ratingRevisionId, buildId);
    }

    public void changesRequested() {
        if (status == SubmissionStatus.SUBMITTED) status = SubmissionStatus.CHANGES_REQUESTED;
    }

    public void readyForRelease() {
        if (status == SubmissionStatus.SUBMITTED) status = SubmissionStatus.READY_FOR_RELEASE;
    }

    public void reopenReview() {
        if (status == SubmissionStatus.CHANGES_REQUESTED) status = SubmissionStatus.SUBMITTED;
    }

    public void applyRating(String ratingCode, String certificationNumber, String issuer,
                            java.time.Instant issuedAt, String country, String ratingPath,
                            String ratingPolicyVersion, String externalApplicationNumber,
                            String externalEvidenceUrl) {
        this.ratingCode = ratingCode;
        this.ratingCertificationNumber = certificationNumber;
        this.ratingIssuer = issuer;
        this.ratingIssuedAt = issuedAt;
        this.ratingCountry = country;
        this.ratingPath = ratingPath;
        this.ratingPolicyVersion = ratingPolicyVersion;
        this.ratingExternalApplicationNumber = externalApplicationNumber;
        this.ratingExternalEvidenceUrl = externalEvidenceUrl;
    }

    public void released() {
        if (status == SubmissionStatus.READY_FOR_RELEASE) status = SubmissionStatus.RELEASED;
    }
}
