package com.stove.studio.core.domain.revision;

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

/**
 * 제출과 출시에 사용할 국가별 가격의 불변 판본.
 */
@Entity
@Getter
@Table(name = "pricing_revision", uniqueConstraints =
        @UniqueConstraint(name = "uk_pricing_revision", columnNames = {"gameId", "revisionNo"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PricingRevision extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 같은 프로젝트 내 자료 판본의 순번. */
    @Column(nullable = false) private int revisionNo;
    /** 자료 또는 정책이 적용되는 국가 코드. */
    @Column(nullable = false, length = 2) private String country;
    /** 금액의 통화 코드. */
    @Column(nullable = false, length = 3) private String currency;
    /** 상품 한 개의 가격으로, 최소 화폐 단위의 정수. */
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
