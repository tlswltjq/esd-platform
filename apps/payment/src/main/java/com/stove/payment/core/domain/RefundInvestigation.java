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
@Table(name = "refund_investigation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundInvestigation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String requestId;
    @Column(nullable = false, updatable = false, length = 36) private String attemptId;
    @Column(nullable = false, updatable = false, length = 100) private String operatorSubject;
    @Column(nullable = false, updatable = false, length = 500) private String note;
    @Column(nullable = false, updatable = false) private Instant recordedAt;
    @Column(nullable = false, updatable = false) private Instant delayedSince;

    public static RefundInvestigation record(RefundExecution execution, RefundInvestigationRequest request, Instant at) {
        if (execution == null || request == null || !execution.getAttemptId().equals(request.attemptId()))
            throw new IllegalArgumentException("Findings must identify the original execution");
        Instant since = execution.confirmationDelayedSince(at);
        if (since == null) throw new BusinessException(ErrorCode.CONFLICT, "현재 장기 미확정인 시도에만 새 조사 기록을 남길 수 있습니다.");
        RefundInvestigation investigation = new RefundInvestigation();
        investigation.requestId = request.requestId(); investigation.attemptId = request.attemptId();
        investigation.operatorSubject = request.operatorSubject(); investigation.note = request.note();
        investigation.recordedAt = at; investigation.delayedSince = since;
        return investigation;
    }

    public void requireSame(RefundInvestigationRequest request) {
        if (request == null || !requestId.equals(request.requestId()) || !attemptId.equals(request.attemptId())
                || !operatorSubject.equals(request.operatorSubject()) || !note.equals(request.note()))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 저장한 조사 기록의 내용과 다릅니다.");
    }

    public RefundInvestigationView view() {
        return new RefundInvestigationView(requestId, attemptId, operatorSubject, note, recordedAt, delayedSince);
    }
}
