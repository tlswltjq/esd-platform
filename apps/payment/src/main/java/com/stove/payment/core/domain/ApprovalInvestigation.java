package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
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

/** Append-only operator findings. These records cannot authorize a financial transition. */
@Entity
@Table(name = "approval_investigation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalInvestigation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String requestId;
    @Column(nullable = false, updatable = false, length = 36) private String approvalId;
    @Column(nullable = false, updatable = false, length = 100) private String operatorSubject;
    @Column(nullable = false, updatable = false, length = 500) private String note;
    @Column(nullable = false, updatable = false) private Instant recordedAt;
    @Column(updatable = false) private Instant reviewStartedAt;

    public static ApprovalInvestigation record(PaymentApproval execution, ApprovalInvestigationRequest request, Instant at) {
        if (execution == null || request == null || !execution.getApprovalId().equals(request.approvalId()))
            throw new IllegalArgumentException("Findings must identify the original execution");
        Instant since = execution.getReviewStartedAt();
        if (execution.getStatus() != PaymentApprovalStatus.REVIEW_REQUIRED) throw new BusinessException(ErrorCode.CONFLICT, "현재 승인 보류인 작업에만 새 조사 기록을 남길 수 있습니다.");
        ApprovalInvestigation investigation = new ApprovalInvestigation();
        investigation.requestId = request.requestId(); investigation.approvalId = request.approvalId();
        investigation.operatorSubject = request.operatorSubject(); investigation.note = request.note();
        investigation.recordedAt = at; investigation.reviewStartedAt = since;
        return investigation;
    }

    public void requireSame(ApprovalInvestigationRequest request) {
        if (request == null || !requestId.equals(request.requestId()) || !approvalId.equals(request.approvalId())
                || !operatorSubject.equals(request.operatorSubject()) || !note.equals(request.note()))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 저장한 조사 기록의 내용과 다릅니다.");
    }

    public ApprovalInvestigationView view() {
        return new ApprovalInvestigationView(requestId, approvalId, operatorSubject, note, recordedAt, reviewStartedAt);
    }
}
