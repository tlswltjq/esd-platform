package com.stove.license.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 소유권(라이선스).
 *
 * <p><b>멱등성이 이 도메인의 핵심.</b> 결제 완료 이벤트는 재전송될 수 있으므로
 * (order_no, product_id) 유니크 제약으로 "한 주문의 한 상품은 한 번만 지급"을 DB 레벨에서 보장한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "license",
        uniqueConstraints = @UniqueConstraint(name = "uk_license_order_product",
                columnNames = {"orderNo", "productId"}))
public class License extends BaseTimeEntity {

    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문·결제·이용권·정산을 연결하는 주문번호. */
    @Column(nullable = false, length = 40)
    private String orderNo;

    /** 구매자를 식별하는 계정 ID. */
    @Column(nullable = false)
    private Long memberId;

    /** Catalog 판매 상품 식별자. */
    @Column(nullable = false)
    private Long productId;

    /** 발급한 이용권을 구별하는 고유 키. */
    @Column(nullable = false, unique = true, length = 40)
    private String licenseKey;

    /** 이용권을 보유 중인지 회수되었는지의 구분. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LicenseStatus status;

    /** 구매에 따른 이용권을 발급한 시각. */
    @Column(nullable = false)
    private Instant issuedAt;

    /** 이용 권한 또는 인증 정보를 회수한 시각. */
    private Instant revokedAt;

    /** 이용권을 회수한 사유. */
    @Column(length = 200)
    private String revokeReason;

    private License(String orderNo, Long memberId, Long productId) {
        this.orderNo = orderNo;
        this.memberId = memberId;
        this.productId = productId;
        this.licenseKey = generateKey();
        this.status = LicenseStatus.ACTIVE;
        this.issuedAt = Instant.now();
    }

    public static License issue(String orderNo, Long memberId, Long productId) {
        return new License(orderNo, memberId, productId);
    }

    /**
     * @return 이번 호출로 실제 상태가 바뀌었으면 true.
     *         호출측은 이 값으로 <b>변화가 있을 때만</b> 회수 이벤트를 발행한다 —
     *         상태가 그대로인데 이벤트를 내보내면 하위 서비스가 헛일을 한다.
     */
    public boolean revoke(String reason) {
        if (status == LicenseStatus.REVOKED) {
            return false;
        }
        this.status = LicenseStatus.REVOKED;
        this.revokedAt = Instant.now();
        this.revokeReason = reason;
        return true;
    }

    public boolean isActive() {
        return status == LicenseStatus.ACTIVE;
    }

    private static String generateKey() {
        String raw = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return String.join("-", raw.substring(0, 5), raw.substring(5, 10), raw.substring(10, 15),
                raw.substring(15, 20));
    }
}
