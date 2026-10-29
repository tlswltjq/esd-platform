package com.stove.payment.core.domain;

import java.time.Instant;

public record OrderAdmissionRequest(String admissionId,String requestEventId,String buyerSubject,PgChargeRequest purchase,Instant expiresAt) {
    public OrderAdmissionRequest {
        if (!PaymentValues.uuid(admissionId) || !PaymentValues.text(requestEventId,64) || !PaymentValues.text(buyerSubject,100)
                || purchase==null || !PaymentValues.microsecond(expiresAt)) throw new IllegalArgumentException("Invalid original purchase admission request");
    }
}
