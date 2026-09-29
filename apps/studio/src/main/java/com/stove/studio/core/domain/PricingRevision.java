package com.stove.studio.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "pricing_revision", uniqueConstraints =
        @UniqueConstraint(name = "uk_pricing_revision", columnNames = {"gameId", "revisionNo"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PricingRevision extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false) private int revisionNo;
    @Column(nullable = false, length = 2) private String country;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false) private long price;

    private PricingRevision(Long gameId, int revisionNo, String country, String currency, long price) {
        this.gameId = gameId;
        this.revisionNo = revisionNo;
        this.country = country;
        this.currency = currency;
        this.price = price;
    }

    public static PricingRevision create(Long gameId, int revisionNo, String country,
                                         String currency, long price) {
        return new PricingRevision(gameId, revisionNo, country, currency, price);
    }
}
