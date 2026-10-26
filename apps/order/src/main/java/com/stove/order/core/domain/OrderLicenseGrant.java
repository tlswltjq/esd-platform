package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "order_license_grant")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLicenseGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String eventId;
    @Column(nullable = false)
    private Instant occurredAt;
    @Column(nullable = false, unique = true, length = 36)
    private String licenseId;
    @Column(nullable = false, unique = true, length = 36)
    private String orderNo;
    @Column(nullable = false, length = 100)
    private String buyerSubject;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false, length = 50)
    private String productCode;
    @Column(nullable = false, length = 64)
    private String paymentId;

    public static OrderLicenseGrant record(LicenseGrant input) {
        if (input == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지급 확인 근거가 필요합니다.");
        }
        OrderLicenseGrant grant = new OrderLicenseGrant();
        grant.eventId = input.eventId();
        grant.occurredAt = input.occurredAt();
        grant.licenseId = input.licenseId();
        grant.orderNo = input.orderNo();
        grant.buyerSubject = input.buyerSubject();
        grant.productId = input.productId();
        grant.productCode = input.productCode();
        grant.paymentId = input.paymentId();
        return grant;
    }

    public LicenseGrant confirmation() {
        return new LicenseGrant(eventId, occurredAt, licenseId, orderNo,
                buyerSubject, productId, productCode, paymentId);
    }

    public void verify(LicenseGrant input) {
        if (!confirmation().equals(input)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 확인한 지급 근거와 다릅니다.");
        }
    }

    public void verifySuspension(LicenseSuspensionConfirmation input) {
        if (input == null || !licenseId.equals(input.licenseId()) || !orderNo.equals(input.orderNo())
                || !buyerSubject.equals(input.buyerSubject()) || !productId.equals(input.productId())
                || !productCode.equals(input.productCode()) || !paymentId.equals(input.paymentId())) {
            throw new RefundCompletionRejected("정지 완료가 확인한 보유권의 구매 근거와 다릅니다.");
        }
    }
}
