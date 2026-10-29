package com.stove.payment.core.domain;

import java.time.Instant;

public record OrderAdmissionFailure(OrderAdmissionRequest admission,String supplierAttemptId,Instant failedAt) {
    public OrderAdmissionFailure {
        if (admission==null || !PaymentValues.text(supplierAttemptId,100) || !PaymentValues.microsecond(failedAt))
            throw new IllegalArgumentException("Verified admission failure is required");
    }
}
