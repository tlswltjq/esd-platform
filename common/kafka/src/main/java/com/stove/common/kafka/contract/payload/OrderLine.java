package com.stove.common.kafka.contract.payload;

import java.math.BigDecimal;

/**
 * 이벤트에 실리는 주문 항목 스냅샷. 금액은 최소 화폐 단위(KRW=원)의 정수.
 *
 * <p>{@code sellerId} 는 정산 배분의 기준 키다. catalog → order → payment → settlement 로
 * 그대로 전파되므로, 이 레코드에 필드를 추가하면 커머스 트랙 전체가 영향을 받는다.
 *
 * @param productId Catalog 판매 상품 식별자.
 * @param productName 주문 시점에 고정한 상품명.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param unitPrice 할인을 반영한 상품 한 개의 실결제 단가.
 * @param quantity 구매할 상품 수량.
 * @param listUnitPrice 할인 전 상품 한 개의 정가.
 * @param discountPerUnit 상품 한 개에 적용하는 할인액.
 * @param promotionId 구매 가격에 적용한 할인 행사 식별자.
 * @param discountBearer 할인 비용 부담 주체(SELLER 또는 PLATFORM).
 * @param settlementBasis 수수료와 판매자 몫을 계산하는 정산 기준액.
 * @param feeRate 정산 기준액에 적용할 수수료율.
 * @param feeAmount 정산 기준액에서 차감할 수수료.
 * @param sellerPayout 수수료 차감 후 판매자에게 배분할 금액.
 */
public record OrderLine(Long productId, String productName, Long sellerId, long unitPrice, int quantity,
                        long listUnitPrice, long discountPerUnit, Long promotionId, String discountBearer,
                        Long settlementBasis, BigDecimal feeRate, Long feeAmount, Long sellerPayout) {

    public OrderLine(Long productId, String productName, Long sellerId, long unitPrice, int quantity) {
        this(productId, productName, sellerId, unitPrice, quantity, unitPrice, 0, null, null,
                null, null, null, null);
    }

    public OrderLine(Long productId, String productName, Long sellerId, long unitPrice, int quantity,
                     long listUnitPrice, long discountPerUnit, Long promotionId, String discountBearer) {
        this(productId, productName, sellerId, unitPrice, quantity, listUnitPrice, discountPerUnit,
                promotionId, discountBearer, null, null, null, null);
    }

    public OrderLine {
        // Payment JSON and earlier events contain only the original five fields.
        if (listUnitPrice == 0 && discountPerUnit == 0 && promotionId == null) {
            listUnitPrice = unitPrice;
        }
        if (quantity < 1 || unitPrice < 1 || listUnitPrice < unitPrice
                || discountPerUnit != listUnitPrice - unitPrice
                || (promotionId == null) != (discountBearer == null)) {
            throw new IllegalArgumentException("Invalid order price snapshot");
        }
        boolean hasTerms = settlementBasis != null || feeRate != null || feeAmount != null || sellerPayout != null;
        if (hasTerms && (settlementBasis == null || feeRate == null || feeAmount == null || sellerPayout == null
                || settlementBasis != Math.multiplyExact(
                        "PLATFORM".equals(discountBearer) ? listUnitPrice : unitPrice, quantity)
                || feeRate.signum() < 0 || feeAmount < 0 || sellerPayout != settlementBasis - feeAmount)) {
            throw new IllegalArgumentException("Invalid settlement terms snapshot");
        }
    }

    public long lineAmount() {
        return Math.multiplyExact(unitPrice, quantity);
    }

    public long listAmount() {
        return Math.multiplyExact(listUnitPrice, quantity);
    }

    public long discountAmount() {
        return Math.multiplyExact(discountPerUnit, quantity);
    }
}
