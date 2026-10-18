package com.stove.settlement.core.domain;

public record ProductOwner(Long productId,String productCode,String sellerSubject) {
    public ProductOwner {
        SettlementValues.require(productId!=null && productId>0 && SettlementValues.productCode(productCode)
                && SettlementValues.text(sellerSubject,100),"상품 소유자가 올바르지 않습니다.");
    }
    public void requireOrder(PaidOrder order) {
        SettlementValues.consistent(productId.equals(order.productId()) && productCode.equals(order.productCode()),
                "주문과 소유자 조회 상품이 다릅니다.");
    }
}
