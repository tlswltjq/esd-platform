package com.stove.catalog.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "catalog_publication_job")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogPublicationJob extends BaseTimeEntity {
    @Id private Long requestId;
    @Column(nullable = false, length = 64) private String sourceEventId;
    @Column(nullable = false) private Instant occurredAt;
    @Column(nullable = false) private Long reviewRequestId;
    @Column(nullable = false) private Long projectId;
    @Column(nullable = false) private long contentVersion;
    @Column(nullable = false, length = 50) private String productCode;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String snapshot;
    @Column(nullable = false, length = 20) private String state;
    private Instant nextAttemptAt;
    @Column(nullable = false) private int attemptCount;
    @Column(length = 500) private String lastError;
    @Column(length = 55) private String traceParent;

    public static CatalogPublicationJob accept(ReleaseSubmission submission, String snapshot, String traceParent) {
        CatalogPublicationJob job = new CatalogPublicationJob();
        job.requestId = submission.releaseRequestId();
        job.sourceEventId = submission.eventId();
        job.occurredAt = submission.occurredAt();
        job.reviewRequestId = submission.reviewRequestId();
        job.projectId = submission.snapshot().projectId();
        job.contentVersion = submission.snapshot().contentVersion();
        job.productCode = submission.snapshot().productCode();
        job.snapshot = snapshot;
        job.state = "PENDING";
        job.nextAttemptAt = Instant.now();
        job.traceParent = traceParent;
        return job;
    }

    public void requireSame(ReleaseSubmission submission, ProjectSnapshot storedSnapshot) {
        if (!requestId.equals(submission.releaseRequestId()) || !sourceEventId.equals(submission.eventId())
                || !reviewRequestId.equals(submission.reviewRequestId()) || !submission.snapshot().equals(storedSnapshot))
            throw new BusinessException(ErrorCode.CONFLICT, "같은 출시 작업의 원본이 다릅니다.");
    }

    public boolean due(Instant now) {
        return "PENDING".equals(state) && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    public void complete() {
        state = "COMPLETED";
        nextAttemptAt = null;
        lastError = null;
    }

    public void failed(String error, Instant now) {
        if (!"PENDING".equals(state)) return;
        attemptCount++;
        long delay = switch (Math.min(attemptCount, 4)) {
            case 1 -> 5L;
            case 2 -> 15L;
            case 3 -> 30L;
            default -> 60L;
        };
        lastError = error == null ? "Publication failed" : error.substring(0, Math.min(error.length(), 500));
        nextAttemptAt = now.plusSeconds(delay);
    }
}
