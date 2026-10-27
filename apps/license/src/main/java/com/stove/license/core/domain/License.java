package com.stove.license.core.domain;

import static com.stove.license.core.domain.LicenseValues.consistent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_license")
public class License {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 36)
    private String licenseId;
    @Column(nullable = false, length = 100)
    private String buyerSubject;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false, length = 50)
    private String productCode;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 64)
    private String eventId;
    @Column(nullable = false)
    private Instant occurredAt;
    @Column(nullable = false, length = 36)
    private String orderNo;
    @Column(nullable = false, length = 64)
    private String requestEventId;
    @Column(nullable = false, length = 64)
    private String paymentResultEventId;
    @Column(nullable = false, length = 64)
    private String paymentId;
    @Column(nullable = false)
    private long contentVersion;
    @Column(nullable = false)
    private long amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false)
    private Instant acquiredAt;
    @Column(nullable = false)
    private Instant receivedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LicenseStatus status = LicenseStatus.ACTIVE;
    @Column(length = 64)
    private String suspensionRequestEventId;

    public void restore(String suspensionRequestId) {
        LicenseValues.require(LicenseValues.text(suspensionRequestId, 64), "정지 요청 식별자가 필요합니다.");
        consistent(status == LicenseStatus.SUSPENDED && suspensionRequestId.equals(suspensionRequestEventId), "복구할 현재 정지가 다릅니다.");
        status = LicenseStatus.ACTIVE; suspensionRequestEventId = null;
    }

    public void suspend(String requestEventId) {
        LicenseValues.require(LicenseValues.text(requestEventId, 64), "정지 요청 식별자가 필요합니다.");
        if (status == LicenseStatus.SUSPENDED && requestEventId.equals(suspensionRequestEventId)) return;
        consistent(status == LicenseStatus.ACTIVE, "다른 요청으로 이미 정지된 보유권입니다.");
        status = LicenseStatus.SUSPENDED;
        suspensionRequestEventId = requestEventId;
    }

    public void verifySuspensionTarget(SuspensionRequest request) {
        LicenseValues.require(request != null, "정지 요청이 필요합니다.");
        consistent(orderNo.equals(request.orderNo()) && buyerSubject.equals(request.buyerSubject())
                && productId.equals(request.productId()) && productCode.equals(request.productCode())
                && paymentId.equals(request.paymentId()), "정지 요청이 보유권의 구매 근거와 다릅니다.");
    }

    public static License grant(PaidOrder order) {
        License license = new License();
        license.licenseId = UUID.randomUUID().toString();
        license.buyerSubject = order.buyerSubject();
        license.productId = order.productId();
        license.productCode = order.productCode();
        license.title = order.title();
        license.eventId = order.eventId();
        license.occurredAt = order.occurredAt();
        license.orderNo = order.orderNo();
        license.requestEventId = order.requestEventId();
        license.paymentResultEventId = order.paymentResultEventId();
        license.paymentId = order.paymentId();
        license.contentVersion = order.contentVersion();
        license.amount = order.amount();
        license.currency = order.currency();
        license.acquiredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        license.receivedAt = license.acquiredAt;
        return license;
    }

    public PaidOrder paidOrder() {
        return new PaidOrder(eventId, occurredAt, orderNo, requestEventId, paymentResultEventId, paymentId,
                buyerSubject, productId, productCode, title, contentVersion, amount, currency);
    }

    public void verify(PaidOrder order) {
        consistent(paidOrder().equals(order), "같은 주문의 구매 근거가 다릅니다.");
    }
}
