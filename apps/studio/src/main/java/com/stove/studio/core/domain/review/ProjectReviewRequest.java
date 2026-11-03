package com.stove.studio.core.domain.review;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.ProjectCommandValues;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Creator's outgoing review request and a copy of its externally reported result. */
@Entity
@Getter
@Table(name = "studio_review_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectReviewRequest extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private long contentVersion;

    @Column(nullable = false)
    private Long buildId;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String snapshot;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 2000)
    private String feedback;

    @Column(length = 100)
    private String actor;

    private Instant resultReceivedAt;
    @Column(length = 64) private String requestEventId;
    @Column(length = 2000) private String withdrawalReason;
    private Instant finalizedAt;

    public static ProjectReviewRequest create(
            Long projectId, long version, Long buildId, String snapshot) {
        ProjectReviewRequest r = new ProjectReviewRequest();
        r.projectId = projectId;
        r.contentVersion = version;
        r.buildId = buildId;
        r.snapshot = snapshot;
        r.status = "REQUESTED";
        return r;
    }

    public boolean approvedFor(long version) {
        return contentVersion == version && "APPROVED".equals(status);
    }

    public void requested(String eventId) {
        requestEventId = eventId;
    }

    public void recordResolution(String finalStatus, String actor, String feedback, Instant at) {
        ProjectCommandValues.requireReviewResult(finalStatus, actor, feedback, at);
        if (!"REQUESTED".equals(status) && !status.equals(finalStatus))
            throw new BusinessException(ErrorCode.CONFLICT, "심사 정본의 최종 결과가 기존 결과와 다릅니다.");
        if ("REQUESTED".equals(status)) {
            status = finalStatus;
            this.actor = actor;
            if ("WITHDRAWN".equals(finalStatus)) withdrawalReason = feedback;
            else this.feedback = feedback;
            resultReceivedAt = Instant.now();
        }
        if (finalizedAt == null) finalizedAt = at;
    }

    public void recordResult(boolean approved, String feedback, String actor) {
        if (!"REQUESTED".equals(status)) return;
        status = approved ? "APPROVED" : "REJECTED";
        this.feedback = feedback;
        this.actor = actor;
        resultReceivedAt = Instant.now();
    }
}
