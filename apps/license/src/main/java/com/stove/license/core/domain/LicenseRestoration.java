package com.stove.license.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "license_restoration") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LicenseRestoration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64) private String requestEventId;
    @Column(nullable = false, length = 64) private String requestFingerprint;
    @Column(nullable = false) private Instant requestOccurredAt;
    @Column(nullable = false, length = 36) private String refundId;
    @Column(nullable = false, length = 36) private String attemptId;
    @Column(nullable = false, length = 64) private String failureEventId;
    @Column(nullable = false, length = 36) private String licenseId;
    @Column(nullable = false, unique = true, length = 64) private String suspensionRequestEventId;
    @Column(nullable = false, unique = true, length = 64) private String completedEventId;
    @Column(nullable = false) private Instant restoredAt;

    public static LicenseRestoration record(License license, RestorationRequest input, Instant at) {
        LicenseValues.require(license != null && input != null && LicenseValues.microsecond(at) && !at.isBefore(input.occurredAt()), "복구 근거가 필요합니다.");
        LicenseValues.consistent(license.getStatus() == LicenseStatus.ACTIVE && license.getLicenseId().equals(input.licenseId()), "실제 복구된 대상이 다릅니다.");
        LicenseRestoration result = new LicenseRestoration(); result.requestEventId = input.eventId(); result.requestFingerprint = input.fingerprint();
        result.requestOccurredAt = input.occurredAt(); result.refundId = input.refundId(); result.attemptId = input.attemptId();
        result.failureEventId = input.failureEventId(); result.licenseId = input.licenseId(); result.suspensionRequestEventId = input.suspensionRequestEventId();
        result.completedEventId = UUID.randomUUID().toString(); result.restoredAt = at; return result;
    }
    public void verify(RestorationRequest input) {
        LicenseValues.consistent(requestFingerprint.equals(input.fingerprint()), "같은 복구 요청의 본문이 다릅니다.");
    }
}
