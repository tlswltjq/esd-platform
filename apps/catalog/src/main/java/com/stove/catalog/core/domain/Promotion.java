package com.stove.catalog.core.domain;

import com.stove.common.event.payload.PromotionWindow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품별 할인액과 적용 기간, 비용 부담 주체를 관리하는 할인 행사.
 */
@Entity
@Getter
@Table(name = "promotion")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Promotion {
    /**
     * 할인 비용을 부담하는 주체.
     */
    public enum Bearer {
        /** 판매자가 할인 비용을 부담. */
        SELLER,
        /** 플랫폼이 할인 비용을 부담. */
        PLATFORM
    }

    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Catalog 판매 상품 식별자. */
    @Column(nullable = false)
    private Long productId;
    /** 판매자를 나타내는 워크스페이스 식별자. */
    @Column(nullable = false)
    private Long sellerId;
    /** 상품 한 개에 적용하는 할인액. */
    @Column(nullable = false)
    private long discountPerUnit;
    /** 할인 비용을 부담하는 판매자 또는 플랫폼. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Bearer bearer;
    /** 할인 적용을 시작하는 시각으로, 경계 시각을 포함. */
    @Column(nullable = false)
    private Instant startsAt;
    /** 할인 적용을 종료하는 시각으로, 경계 시각을 제외. */
    @Column(nullable = false)
    private Instant endsAt;
    /** 예정보다 일찍 할인을 중단한 시각. */
    private Instant stoppedAt;

    private Promotion(Long productId, Long sellerId, long discountPerUnit, Bearer bearer,
                      Instant startsAt, Instant endsAt) {
        this.productId = productId;
        this.sellerId = sellerId;
        this.discountPerUnit = discountPerUnit;
        this.bearer = bearer;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public static Promotion schedule(Long productId, Long sellerId, long discountPerUnit,
                                     Bearer bearer, Instant startsAt, Instant endsAt) {
        return new Promotion(productId, sellerId, discountPerUnit, bearer, startsAt, endsAt);
    }

    public boolean overlaps(Instant start, Instant end) {
        Instant effectiveEnd = stoppedAt == null || stoppedAt.isAfter(endsAt) ? endsAt : stoppedAt;
        return startsAt.isBefore(end) && start.isBefore(effectiveEnd);
    }

    public boolean activeAt(Instant instant) {
        return stoppedAt == null && !instant.isBefore(startsAt) && instant.isBefore(endsAt);
    }

    public void stop(Instant instant) {
        if (stoppedAt == null) stoppedAt = instant;
    }

    public PromotionWindow window() {
        return new PromotionWindow(id, discountPerUnit, bearer.name(), startsAt, endsAt);
    }
}
