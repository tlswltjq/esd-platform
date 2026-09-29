package com.stove.common.event.payload;

/**
 * 이벤트에 실리는 주문 항목 스냅샷. 금액은 최소 화폐 단위(KRW=원)의 정수.
 *
 * <p>{@code sellerId} 는 정산 배분의 기준 키다. catalog → order → payment → settlement 로
 * 그대로 전파되므로, 이 레코드에 필드를 추가하면 커머스 트랙 전체가 영향을 받는다.
 */
public record OrderLine(Long productId, String productName, Long sellerId, long unitPrice, int quantity,
                        long listUnitPrice, long discountPerUnit, Long promotionId, String discountBearer,
                        Long settlementBasis, java.math.BigDecimal feeRate, Long feeAmount, Long sellerPayout) {

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
