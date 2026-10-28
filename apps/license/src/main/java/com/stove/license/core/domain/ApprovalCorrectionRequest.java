package com.stove.license.core.domain;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public record ApprovalCorrectionRequest(String eventId, Instant occurredAt, String refundId, String reviewId, String stopRequestId, String licenseId, String orderNo, String buyerSubject, Long productId, String productCode, String paymentId, String suspensionRequestEventId, String suspensionEventId) {
    public ApprovalCorrectionRequest {
        LicenseValues.require(LicenseValues.text(eventId,64) && LicenseValues.microsecond(occurredAt)
                && LicenseValues.uuid(refundId) && LicenseValues.uuid(reviewId) && LicenseValues.uuid(stopRequestId)
                && LicenseValues.uuid(licenseId) && LicenseValues.uuid(orderNo) && LicenseValues.text(buyerSubject,100)
                && productId!=null && productId>0 && LicenseValues.productCode(productCode) && LicenseValues.text(paymentId,64)
                && LicenseValues.text(suspensionRequestEventId,64) && LicenseValues.text(suspensionEventId,64),"승인 정정 복구 근거가 올바르지 않습니다.");
    }
    public String fingerprint() {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for (String field:List.of(eventId,occurredAt.toString(),refundId,reviewId,stopRequestId,licenseId,orderNo,buyerSubject,
                    productId.toString(),productCode,paymentId,suspensionRequestEventId,suspensionEventId)) {
                byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length+":").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
