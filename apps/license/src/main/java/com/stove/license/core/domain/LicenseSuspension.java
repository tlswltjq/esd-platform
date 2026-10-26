package com.stove.license.core.domain;

import static com.stove.license.core.domain.LicenseValues.consistent;
import static com.stove.license.core.domain.LicenseValues.microsecond;
import static com.stove.license.core.domain.LicenseValues.require;

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

@Entity @Table(name = "license_suspension")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LicenseSuspension {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String requestEventId;
    @Column(nullable = false)
    private Instant requestOccurredAt;
    @Column(nullable = false, length = 36)
    private String refundId;
    @Column(nullable = false, length = 36)
    private String licenseId;
    @Column(nullable = false, length = 36)
    private String orderNo;
    @Column(nullable = false, length = 100)
    private String buyerSubject;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false, length = 50)
    private String productCode;
    @Column(nullable = false, length = 64)
    private String paymentId;
    @Column(nullable = false)
    private Instant suspendedAt;
    @Column(nullable = false, unique = true, length = 64)
    private String completedEventId;

    public static LicenseSuspension record(License license, SuspensionRequest input, Instant suspendedAt) {
        require(license != null && input != null && microsecond(suspendedAt), "정지 처리 근거가 필요합니다.");
        license.verifySuspensionTarget(input);
        consistent(license.getStatus() == LicenseStatus.SUSPENDED
                && input.eventId().equals(license.getSuspensionRequestEventId()), "정지된 요청과 근거가 다릅니다.");
        LicenseSuspension result = new LicenseSuspension();
        result.requestEventId = input.eventId(); result.requestOccurredAt = input.occurredAt();
        result.refundId = input.refundId(); result.licenseId = license.getLicenseId();
        result.orderNo = input.orderNo(); result.buyerSubject = input.buyerSubject();
        result.productId = input.productId(); result.productCode = input.productCode();
        result.paymentId = input.paymentId(); result.suspendedAt = suspendedAt;
        result.completedEventId = UUID.randomUUID().toString();
        return result;
    }

    public SuspensionRequest request() {
        return new SuspensionRequest(requestEventId, requestOccurredAt, refundId, orderNo,
                buyerSubject, productId, productCode, paymentId);
    }

    public void verify(License license, SuspensionRequest input) {
        consistent(licenseId.equals(license.getLicenseId()) && request().equals(input),
                "같은 정지 요청의 본문 또는 대상 보유권이 다릅니다.");
    }
}
