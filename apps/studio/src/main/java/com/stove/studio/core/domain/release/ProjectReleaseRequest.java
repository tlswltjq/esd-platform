package com.stove.studio.core.domain.release;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.ProjectCommandValues;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    @Column(length = 100) private String withdrawnBy;
    @Column(length = 2000) private String withdrawalReason;
    private Instant finalizedAt;

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

    public void recordResolution(String finalStatus, String actor, String reason, Instant at) {
        ProjectCommandValues.requireReleaseResult(finalStatus, reason);
        if (actor == null || actor.isBlank() || actor.length() > 100 || at == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출시 최종 근거가 올바르지 않습니다.");
        if (!"REQUESTED".equals(status) && !status.equals(finalStatus))
            throw new BusinessException(ErrorCode.CONFLICT, "출시 정본의 최종 결과가 기존 결과와 다릅니다.");
        if ("REQUESTED".equals(status)) {
            status = finalStatus;
            failureReason = "REJECTED".equals(finalStatus) ? reason : null;
            if ("WITHDRAWN".equals(finalStatus)) {
                withdrawnBy = actor;
                withdrawalReason = reason;
            }
            resultReceivedAt = Instant.now();
        }
        if (finalizedAt == null) finalizedAt = at;
    }
}
