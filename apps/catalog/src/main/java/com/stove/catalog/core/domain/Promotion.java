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

@Entity
@Getter
@Table(name = "promotion")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Promotion {
    public enum Bearer { SELLER, PLATFORM }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long productId;
    @Column(nullable = false)
    private Long sellerId;
    @Column(nullable = false)
    private long discountPerUnit;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Bearer bearer;
    @Column(nullable = false)
    private Instant startsAt;
    @Column(nullable = false)
    private Instant endsAt;
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
