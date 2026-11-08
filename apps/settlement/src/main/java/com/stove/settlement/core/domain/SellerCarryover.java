package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable carry ledger row written once inside the seller month closing transaction (decisions 121·130). */
@Entity @Table(name="settlement_seller_carryover") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SellerCarryover {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=100,updatable=false) private String sellerSubject;
    @Column(nullable=false,length=7,updatable=false) private String settlementMonth;
    @Column(nullable=false,updatable=false) private long netSellerPayoutAmount;
    @Column(nullable=false,updatable=false) private long carriedIn;
    @Column(nullable=false,updatable=false) private long payable;
    @Column(nullable=false,updatable=false) private long carriedOut;
    @Column(length=7,updatable=false) private String sourceMonth;
    @Column(nullable=false,updatable=false) private Instant createdAt;

    /**
     * @param sourceMonth latest earlier CLOSED month of the same seller, or null when there is none
     * @param carriedIn that month's carriedOut (0 when sourceMonth is null)
     */
    public static SellerCarryover record(SellerMonthlySettlement closed, String sourceMonth, long carriedIn, Instant now) {
        SettlementValues.require(closed != null && closed.isClosed() && SettlementValues.microsecond(now), "마감한 판매자 월이 필요합니다.");
        if (sourceMonth == null) SettlementValues.require(carriedIn == 0, "이월 출처 없이 이월액을 받을 수 없습니다.");
        else SettlementValues.require(new SettlementMonth(sourceMonth).value().compareTo(closed.getSettlementMonth()) < 0,
                "이월 출처는 마감 월보다 이전 월이어야 합니다.");
        SellerMonthBalance balance = SellerMonthBalance.settle(closed.getNetSellerPayoutAmount(), carriedIn);
        SellerCarryover result = new SellerCarryover();
        result.sellerSubject = closed.getSellerSubject(); result.settlementMonth = closed.getSettlementMonth();
        result.netSellerPayoutAmount = closed.getNetSellerPayoutAmount(); result.carriedIn = balance.carriedIn();
        result.payable = balance.payable(); result.carriedOut = balance.carriedOut();
        result.sourceMonth = sourceMonth; result.createdAt = now;
        return result;
    }
    public SellerMonthBalance balance() { return new SellerMonthBalance(carriedIn, payable, carriedOut); }
}
