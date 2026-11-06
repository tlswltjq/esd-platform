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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 구매 근거 하나를 라이브러리에 등록한 한 번의 보유권. 등록마다 새 행이다(결정 95). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "license_registration")
public class LicenseRegistration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 36)
    private String registrationId;
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
    @Column(nullable = false, length = 200)
    private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LicenseStatus status;
    @Column(nullable = false)
    private Instant registeredAt;
    private Instant endedAt;

    static LicenseRegistration open(License purchase, Instant at) {
        LicenseRegistration registration = new LicenseRegistration();
        registration.registrationId = UUID.randomUUID().toString();
        registration.licenseId = purchase.getLicenseId();
        registration.orderNo = purchase.getOrderNo();
        registration.buyerSubject = purchase.getBuyerSubject();
        registration.productId = purchase.getProductId();
        registration.productCode = purchase.getProductCode();
        registration.title = purchase.getTitle();
        registration.status = LicenseStatus.ACTIVE;
        registration.registeredAt = at;
        return registration;
    }

    boolean belongsTo(License purchase) {
        return licenseId.equals(purchase.getLicenseId()) && orderNo.equals(purchase.getOrderNo());
    }

    void suspend() {
        consistent(status == LicenseStatus.ACTIVE, "쓸 수 있는 보유권만 정지할 수 있습니다.");
        status = LicenseStatus.SUSPENDED;
    }

    void activate() {
        consistent(status == LicenseStatus.SUSPENDED, "정지된 보유권만 복구할 수 있습니다.");
        status = LicenseStatus.ACTIVE;
    }

    void end(Instant at) {
        consistent(status != LicenseStatus.ENDED, "이미 끝난 보유권입니다.");
        status = LicenseStatus.ENDED;
        endedAt = at;
    }
}
