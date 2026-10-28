package com.stove.license.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="license_approval_correction") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class LicenseApprovalCorrection {
    @Id @Column(length=64) private String requestEventId;
    @Column(nullable=false,length=64) private String requestFingerprint;
    @Column(nullable=false) private Instant requestOccurredAt;
    @Column(nullable=false,unique=true,length=36) private String stopRequestId;
    @Column(nullable=false,unique=true,length=64) private String suspensionRequestEventId;
    @Column(nullable=false,length=36) private String licenseId;
    @Column(nullable=false,unique=true,length=64) private String completedEventId;
    @Column(nullable=false) private Instant restoredAt;
    public static LicenseApprovalCorrection record(License license, ApprovalCorrectionRequest input, Instant at) {
        LicenseValues.require(license!=null && input!=null && LicenseValues.microsecond(at) && !at.isBefore(input.occurredAt()),"복구 시각이 올바르지 않습니다.");
        LicenseValues.consistent(license.getStatus()==LicenseStatus.ACTIVE && license.getLicenseId().equals(input.licenseId()),"실제 복구 대상이 다릅니다.");
        LicenseApprovalCorrection result=new LicenseApprovalCorrection(); result.requestEventId=input.eventId(); result.requestFingerprint=input.fingerprint();
        result.requestOccurredAt=input.occurredAt(); result.stopRequestId=input.stopRequestId(); result.suspensionRequestEventId=input.suspensionRequestEventId();
        result.licenseId=input.licenseId(); result.completedEventId=UUID.randomUUID().toString(); result.restoredAt=at; return result;
    }
    public void verify(ApprovalCorrectionRequest input) { LicenseValues.consistent(requestFingerprint.equals(input.fingerprint()),"복구 재전송 본문이 다릅니다."); }
}
