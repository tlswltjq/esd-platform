package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundAttribution;
import com.stove.payment.core.domain.RefundClosure;
import com.stove.payment.core.domain.RefundClosureView;
import java.time.Instant;

/** The append-only operator closure record of a refund execution (decision 127). It never contains PG keys or request bodies. */
public record RefundClosureRecordResponse(String requestId, String operatorSubject, RefundAttribution attribution, String note,
        RefundClosure.Outcome outcome, Instant evidenceRefundedAt, Instant evidenceObservedAt, Instant closedAt) {
    public static RefundClosureRecordResponse from(RefundClosureView view) {
        return view == null ? null : new RefundClosureRecordResponse(view.requestId(), view.operatorSubject(), view.attribution(),
                view.note(), view.outcome(), view.evidenceRefundedAt(), view.evidenceObservedAt(), view.closedAt());
    }
}
