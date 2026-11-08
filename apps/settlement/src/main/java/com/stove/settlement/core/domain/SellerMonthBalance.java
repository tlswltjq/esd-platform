package com.stove.settlement.core.domain;

/**
 * Seller amount basis of one month (decisions 121·130): the carry received from the latest earlier CLOSED month,
 * the payable basis that never goes below zero, and the negative remainder handed to the next closing.
 * OPEN months have no fixed values yet, so all three are null.
 */
public record SellerMonthBalance(Long carriedIn, Long payable, Long carriedOut) {
    public SellerMonthBalance {
        boolean open = carriedIn == null && payable == null && carriedOut == null;
        boolean fixed = carriedIn != null && payable != null && carriedOut != null
                && carriedIn <= 0 && payable >= 0 && carriedOut <= 0 && (payable == 0 || carriedOut == 0);
        SettlementValues.require(open || fixed, "이월·지급 기준액이 올바르지 않습니다.");
    }
    public static SellerMonthBalance unsettled() { return new SellerMonthBalance(null, null, null); }
    /** {@code payable = max(0, net + carriedIn)}; a negative total is handed over whole as carriedOut. */
    public static SellerMonthBalance settle(long net, long carriedIn) {
        SettlementValues.require(carriedIn <= 0, "들어온 이월액은 0 이하여야 합니다.");
        long total = Math.addExact(net, carriedIn);
        return total < 0 ? new SellerMonthBalance(carriedIn, 0L, total) : new SellerMonthBalance(carriedIn, total, 0L);
    }
    /** OPEN: not fixed yet. CLOSED with a ledger row: that row. CLOSED before the ledger existed: read as carriedIn 0 (decision 130). */
    public static SellerMonthBalance of(SellerMonthlySettlement month, SellerCarryover ledger) {
        SettlementValues.require(month != null, "판매자 월이 필요합니다.");
        if (!month.isClosed()) return unsettled();
        if (ledger == null) return settle(month.getNetSellerPayoutAmount(), 0);
        SettlementValues.consistent(ledger.getSellerSubject().equals(month.getSellerSubject())
                && ledger.getSettlementMonth().equals(month.getSettlementMonth()), "다른 판매자 월의 이월 원장입니다.");
        return ledger.balance();
    }
}
