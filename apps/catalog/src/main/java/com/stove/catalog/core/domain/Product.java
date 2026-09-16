package com.stove.catalog.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 마스터. 커머스 트랙 전체가 참조하는 최대 접점 애그리거트이므로
 * 가격/상태 변경 규칙을 엔티티 안에 가둔다.
 */
@Entity
@Getter
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String productCode;

    /** studio 의 게임 프로젝트 ID. 크리에이터 트랙과의 연결 고리. */
    private Long gameId;

    /** Store와 Download가 함께 바라보는 현재 공개 릴리스 스냅샷. */
    private Long currentReleaseId;

    private Long currentBuildId;

    private Long metadataRevision;

    @Column(nullable = false, length = 200)
    private String name;

    /** 판매자 ID. 자체 게임은 SELF_SELLER_ID, 입점사는 스튜디오 판매자 ID */
    @Column(nullable = false)
    private Long sellerId;

    /** 최소 화폐 단위 정수 가격(KRW). 부동소수 오차를 원천 차단한다. */
    @Column(nullable = false)
    private long price;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    /** 게임물관리위원회 등급 코드(ALL/12/15/18) */
    @Column(length = 10)
    private String ratingCode;

    private Product(String productCode, String name, Long sellerId, long price, String currency) {
        this.productCode = productCode;
        this.name = name;
        this.sellerId = sellerId;
        this.price = price;
        this.currency = currency;
        this.status = ProductStatus.DRAFT;
    }

    public static Product draft(String productCode, String name, Long sellerId, long price, String currency) {
        return new Product(productCode, name, sellerId, price, currency);
    }

    /** 심의 승인 이벤트로 상품 마스터를 최초 생성하는 경로 */
    public static Product fromReview(Long gameId, String productCode, String name, Long sellerId,
                                     long price, String currency, String ratingCode) {
        Product product = new Product(productCode, name, sellerId, price, currency);
        product.gameId = gameId;
        product.applyReviewApproval(ratingCode);
        return product;
    }

    public static Product fromRelease(Long gameId, String productCode, String name, Long sellerId,
                                      long price, String currency, String ratingCode,
                                      Long releaseId, Long buildId, Long metadataRevision) {
        Product product = new Product(productCode, name, sellerId, price, currency);
        product.applyRelease(gameId, name, sellerId, price, currency, ratingCode,
                releaseId, buildId, metadataRevision);
        return product;
    }

    /** 공개된 불변 릴리스 스냅샷만 상품 마스터에 원자적으로 반영한다. */
    public void applyRelease(Long gameId, String name, Long sellerId, long price, String currency,
                             String ratingCode, Long releaseId, Long buildId, Long metadataRevision) {
        this.gameId = gameId;
        this.name = name;
        this.sellerId = sellerId;
        this.price = price;
        this.currency = currency;
        this.ratingCode = ratingCode;
        this.currentReleaseId = releaseId;
        this.currentBuildId = buildId;
        this.metadataRevision = metadataRevision;
        this.status = ProductStatus.ON_SALE;
    }

    /** review 승인 이벤트 수신 시 호출. 심의 결과를 반영하고 판매 가능 상태로 올린다. */
    public void applyReviewApproval(String ratingCode) {
        this.ratingCode = ratingCode;
        if (this.status == ProductStatus.DRAFT || this.status == ProductStatus.REVIEWING) {
            this.status = ProductStatus.APPROVED;
        }
    }

    public void openSale() {
        if (currentReleaseId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "공개된 릴리스가 없는 상품은 판매할 수 없습니다.");
        }
        if (this.status != ProductStatus.APPROVED && this.status != ProductStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.CONFLICT, "심의 승인 상태에서만 판매를 시작할 수 있습니다.");
        }
        this.status = ProductStatus.ON_SALE;
    }

    public void suspend() {
        this.status = ProductStatus.SUSPENDED;
    }

    public void requirePurchasable() {
        if (!status.purchasable()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_ON_SALE,
                    "판매 중이 아닌 상품입니다. productId=" + id);
        }
    }
}
