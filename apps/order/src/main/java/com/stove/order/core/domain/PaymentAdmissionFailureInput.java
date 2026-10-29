package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

public record PaymentAdmissionFailureInput(String admissionId,String orderNo,String paymentId,String supplierAttemptId,Instant failedAt) {
    public PaymentAdmissionFailureInput {
        requireIdentity(admissionId,orderNo,paymentId);
        requireEvidence(supplierAttemptId,failedAt);
    }
    private static void requireIdentity(String admissionId,String orderNo,String paymentId) {
        if (admissionId==null || !admissionId.matches(OrderValues.UUID_PATTERN) || orderNo==null || !orderNo.matches(OrderValues.UUID_PATTERN)
                || paymentId==null || paymentId.isBlank() || paymentId.length()>64)
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"Invalid admission failure identity");
    }
    private static void requireEvidence(String supplierAttemptId,Instant failedAt) {
        if (supplierAttemptId==null || supplierAttemptId.isBlank() || supplierAttemptId.length()>100
                || failedAt==null || failedAt.getNano()%1000!=0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"Verified attempt failure is required");
    }
}
