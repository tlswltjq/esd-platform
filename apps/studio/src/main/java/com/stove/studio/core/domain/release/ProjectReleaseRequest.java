package com.stove.studio.core.domain.release;

import com.stove.common.jpa.BaseTimeEntity;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;

/** Outgoing release request. Completion is reported by the external release owner. */
@Entity
@Getter
@Table(name = "studio_release_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectReleaseRequest extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long reviewRequestId;

    @Column(nullable = false)
    private long contentVersion;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 64)
    private String requestEventId;

    @Column(length = 2000)
    private String failureReason;

    private Instant resultReceivedAt;

    public static ProjectReleaseRequest create(Long projectId, Long reviewRequestId, long version) {
        ProjectReleaseRequest r = new ProjectReleaseRequest();
        r.projectId = projectId;
        r.reviewRequestId = reviewRequestId;
        r.contentVersion = version;
        r.status = "REQUESTED";
        return r;
    }

    public void requested(String eventId) {
        requestEventId = eventId;
    }

    public void recordResult(boolean success, String reason) {
        if (!"REQUESTED".equals(status)) return;
        status = success ? "COMPLETED" : "REJECTED";
        failureReason = reason;
        resultReceivedAt = Instant.now();
    }
}
