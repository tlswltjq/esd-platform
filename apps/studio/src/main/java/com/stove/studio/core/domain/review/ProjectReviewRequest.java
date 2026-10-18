package com.stove.studio.core.domain.review;

import com.stove.common.jpa.BaseTimeEntity;
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

    public void recordResult(boolean approved, String feedback, String actor) {
        if (!"REQUESTED".equals(status)) return;
        status = approved ? "APPROVED" : "REJECTED";
        this.feedback = feedback;
        this.actor = actor;
        resultReceivedAt = Instant.now();
    }
}
