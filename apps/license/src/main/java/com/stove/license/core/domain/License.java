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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String orderNo;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long productId;

    /** CD키/라이선스 키 */
    @Column(nullable = false, unique = true, length = 40)
    private String licenseKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LicenseStatus status;

    @Column(nullable = false)
    private Instant issuedAt;

    private Instant revokedAt;

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
