package com.stove.studio.core.domain;

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

@Entity
@Getter
@Table(name = "submission", uniqueConstraints =
        @UniqueConstraint(name = "uk_submission_sequence", columnNames = {"gameId", "sequenceNo"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Submission extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false) private Long workspaceId;
    @Column(nullable = false) private int sequenceNo;
    @Column(nullable = false) private Long metadataRevisionId;
    @Column(nullable = false) private Long pricingRevisionId;
    @Column(nullable = false) private Long ratingRevisionId;
    @Column(nullable = false) private Long buildId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private SubmissionStatus status;
    @Version @Column(nullable = false) private long entityVersion;
    @Column(length = 10) private String ratingCode;
    @Column(length = 100) private String ratingCertificationNumber;
    @Column(length = 100) private String ratingIssuer;
    private java.time.Instant ratingIssuedAt;
    @Column(length = 2) private String ratingCountry;
    @Column(length = 30) private String ratingPath;
    @Column(length = 30) private String ratingPolicyVersion;
    @Column(length = 100) private String ratingExternalApplicationNumber;
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
