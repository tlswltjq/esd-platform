package com.stove.payment.core.domain;

import java.time.Instant;
import java.time.Duration;

public record OrderAdmissionReceipt(String admissionId,String orderNo,String paymentId,Instant admittedAt,Instant expiresAt) {
    public OrderAdmissionReceipt {
        if (!PaymentValues.uuid(admissionId) || !PaymentValues.uuid(orderNo) || !PaymentValues.text(paymentId,64)
                || !PaymentValues.microsecond(admittedAt) || !PaymentValues.microsecond(expiresAt) || !admittedAt.isBefore(expiresAt))
            throw new IllegalArgumentException("Invalid Order admission receipt");
    }
    public void requireRequest(OrderAdmissionRequest request) {
        if (!admissionId.equals(request.admissionId()) || !orderNo.equals(request.purchase().orderNo()) || !paymentId.equals(request.purchase().paymentId())
                || !expiresAt.equals(request.expiresAt()) || admittedAt.isBefore(expiresAt.minus(Duration.ofMinutes(30))))
            throw new IllegalArgumentException("Order admission does not identify the purchase");
    }
}
