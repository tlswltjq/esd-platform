package com.stove.license.core.domain;

import static com.stove.license.core.domain.LicenseValues.consistent;

import com.stove.common.web.error.BusinessException;
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
    @Column(length = 64)
    private String refundedSuspensionRequestEventId;
    private Instant refundedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private PurchaseStatus status = PurchaseStatus.STORED;
    @Enumerated(EnumType.STRING) @Column(length = 20)
    private PurchaseStatus suspendedFrom;
    @Column(length = 64)
    private String suspensionRequestEventId;

    /** 보관함 항목을 라이브러리에 등록한다(결정 93). otherActive는 같은 고객·상품의 쓸 수 있는 보유권 유무다. */
    public LicenseRegistration register(boolean otherActive, Instant at) {
        if (status != PurchaseStatus.STORED) throw new BusinessException(LicenseErrorCode.PURCHASE_NOT_REGISTRABLE);
        if (otherActive) throw new BusinessException(LicenseErrorCode.PRODUCT_ALREADY_OWNED);
        status = PurchaseStatus.REGISTERED;
        return LicenseRegistration.open(this, at);
    }

    /** 환불을 위해 구매 근거를 정지한다(결정 94). 등록됐으면 현재 보유권도 정지한다. */
    public void suspend(String requestEventId, LicenseRegistration current) {
        LicenseValues.require(LicenseValues.text(requestEventId, 64), "정지 요청 식별자가 필요합니다.");
        if (status == PurchaseStatus.SUSPENDED && requestEventId.equals(suspensionRequestEventId)) return;
        consistent(status == PurchaseStatus.STORED || status == PurchaseStatus.REGISTERED, "다른 요청으로 이미 정지됐거나 끝난 구매입니다.");
        if (status == PurchaseStatus.REGISTERED) {
            requireCurrent(current);
            current.suspend();
        }
        suspendedFrom = status;
        status = PurchaseStatus.SUSPENDED;
        suspensionRequestEventId = requestEventId;
    }

    /**
     * 확정 실패·승인 정정으로 정지를 푼다(결정 96). 등록됐던 구매분인데 같은 상품의 다른 보유권이 이미 쓰이고 있으면
     * 옛 보유권을 끝내고 보관함으로 돌려보낸다.
     * @return 보관함으로 돌아갔으면 true
     */
    public boolean restore(String suspensionRequestId, LicenseRegistration current, boolean otherActive, Instant at) {
        LicenseValues.require(LicenseValues.text(suspensionRequestId, 64), "정지 요청 식별자가 필요합니다.");
        consistent(status == PurchaseStatus.SUSPENDED && suspensionRequestId.equals(suspensionRequestEventId), "복구할 현재 정지가 다릅니다.");
        boolean returned = false;
        if (suspendedFrom == PurchaseStatus.REGISTERED) {
            requireCurrent(current);
            if (otherActive) {
                current.end(at);
                status = PurchaseStatus.STORED;
                returned = true;
            } else {
                current.activate();
                status = PurchaseStatus.REGISTERED;
            }
        } else {
            status = PurchaseStatus.STORED;
        }
        suspendedFrom = null;
        suspensionRequestEventId = null;
        return returned;
    }

    /** Order가 확정한 환불 완료로 구매를 끝낸다. 재전달은 바꾸지 않는다. */
    public boolean refund(String suspensionRequestId, LicenseRegistration current, Instant at) {
        LicenseValues.require(LicenseValues.text(suspensionRequestId, 64), "정지 요청 식별자가 필요합니다.");
        if (status == PurchaseStatus.REFUNDED) {
            consistent(suspensionRequestId.equals(refundedSuspensionRequestEventId), "다른 정지의 환불 완료입니다.");
            return false;
        }
        consistent(status == PurchaseStatus.SUSPENDED && suspensionRequestId.equals(suspensionRequestEventId), "환불 완료가 현재 정지와 다릅니다.");
        if (suspendedFrom == PurchaseStatus.REGISTERED) {
            requireCurrent(current);
            current.end(at);
        }
        status = PurchaseStatus.REFUNDED;
        refundedSuspensionRequestEventId = suspensionRequestId;
        refundedAt = at;
        suspendedFrom = null;
        suspensionRequestEventId = null;
        return true;
    }

    private void requireCurrent(LicenseRegistration current) {
        consistent(current != null && current.belongsTo(this) && current.getStatus() != LicenseStatus.ENDED,
                "구매 근거의 현재 보유권이 없습니다.");
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
