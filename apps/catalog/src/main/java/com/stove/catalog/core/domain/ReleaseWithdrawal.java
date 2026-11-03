package com.stove.catalog.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "catalog_release_withdrawal")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReleaseWithdrawal extends BaseTimeEntity {
    @Id private Long withdrawalId;
    @Column(nullable = false) private Long requestId;
    @Column(nullable = false) private Long projectId;
    @Column(nullable = false) private long contentVersion;
    @Column(nullable = false, length = 50) private String productCode;
    @Column(nullable = false, length = 100) private String ownerSubject;
    @Column(length = 2000) private String reason;
    @Column(nullable = false) private Instant requestedAt;
    @Column(nullable = false) private boolean resolved;

    public static ReleaseWithdrawal receive(ReleaseWithdrawalInput input) {
        ProjectWithdrawalRequest request = input.request();
        ReleaseWithdrawal withdrawal = new ReleaseWithdrawal();
        withdrawal.withdrawalId = request.withdrawalId();
        withdrawal.requestId = request.requestId();
        withdrawal.projectId = request.projectId();
        withdrawal.contentVersion = request.contentVersion();
        withdrawal.productCode = request.productCode();
        withdrawal.ownerSubject = request.ownerSubject();
        withdrawal.reason = request.reason();
        withdrawal.requestedAt = input.occurredAt();
        return withdrawal;
    }

    public void requireSame(ProjectWithdrawalRequest request) {
        if (!instruction().equals(request))
            throw new BusinessException(ErrorCode.CONFLICT, "같은 출시 철회 명령의 내용이 다릅니다.");
    }

    public ProjectWithdrawalRequest instruction() {
        return new ProjectWithdrawalRequest(withdrawalId, requestId, projectId,
                contentVersion, productCode, ownerSubject, reason);
    }

    public ProjectWithdrawalResult resolve(CatalogRelease release, ProjectSnapshot snapshot) {
        if (!requestId.equals(release.getReleaseRequestId()) || !projectId.equals(release.getProjectId())
                || contentVersion != release.getContentVersion() || !productCode.equals(release.getProductCode())
                || !ownerSubject.equals(snapshot.ownerSubject()))
            throw new BusinessException(ErrorCode.CONFLICT, "출시 철회 대상이 일치하지 않습니다.");
        resolved = true;
        boolean withdrawn = "WITHDRAWN".equals(release.getStatus());
        return new ProjectWithdrawalResult(withdrawalId, requestId, projectId, contentVersion,
                productCode, release.getStatus(), withdrawn ? release.getWithdrawnBy() : "catalog",
                withdrawn ? release.getWithdrawalReason() : release.getReason(),
                release.getProcessedAt(), release.getRequestEventId());
    }
}
