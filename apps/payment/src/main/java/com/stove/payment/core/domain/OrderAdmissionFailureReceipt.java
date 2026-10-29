package com.stove.payment.core.domain;

import java.time.Instant;

public record OrderAdmissionFailureReceipt(String admissionId,String orderNo,String paymentId,Instant closedAt,String orderStatus) {
    public OrderAdmissionFailureReceipt {
        if (!PaymentValues.uuid(admissionId) || !PaymentValues.uuid(orderNo) || !PaymentValues.text(paymentId,64)
                || !PaymentValues.microsecond(closedAt) || !("PENDING".equals(orderStatus) || "EXPIRED".equals(orderStatus)))
            throw new IllegalArgumentException("Invalid failure receipt");
    }
    public void requireRequest(OrderAdmissionFailure request) {
        if (!admissionId.equals(request.admission().admissionId()) || !orderNo.equals(request.admission().purchase().orderNo())
                || !paymentId.equals(request.admission().purchase().paymentId()) || closedAt.isBefore(request.failedAt()))
            throw new IllegalArgumentException("Failure receipt does not identify the approval");
    }
}
