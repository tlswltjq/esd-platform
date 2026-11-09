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
    @Column(nullable=false) private boolean returnedToStorage;
    public static LicenseApprovalCorrection record(License license, ApprovalCorrectionRequest input, Instant at, boolean returnedToStorage) {
        LicenseValues.require(license!=null && input!=null && LicenseValues.microsecond(at),"복구 시각이 올바르지 않습니다.");
        LicenseValues.consistent((license.getStatus()==PurchaseStatus.REGISTERED || license.getStatus()==PurchaseStatus.STORED) && license.getLicenseId().equals(input.licenseId()),"실제 복구 대상이 다릅니다.");
        LicenseApprovalCorrection result=new LicenseApprovalCorrection(); result.requestEventId=input.eventId(); result.requestFingerprint=input.fingerprint();
        result.requestOccurredAt=input.occurredAt(); result.stopRequestId=input.stopRequestId(); result.suspensionRequestEventId=input.suspensionRequestEventId();
        result.licenseId=input.licenseId(); result.completedEventId=UUID.randomUUID().toString(); result.restoredAt=at; result.returnedToStorage=returnedToStorage; return result;
    }
    public void verify(ApprovalCorrectionRequest input) { LicenseValues.consistent(requestFingerprint.equals(input.fingerprint()),"복구 재전송 본문이 다릅니다."); }
}
