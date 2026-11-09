package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Per-seller serialization row (V14): posting and closing lock it before reading or creating any month row of the seller. */
@Entity @Table(name="settlement_seller_lock") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SellerLock {
    @Id @Column(nullable=false,length=100,updatable=false) private String sellerSubject;
    @Column(nullable=false,updatable=false) private Instant createdAt;

    public static SellerLock of(String seller,Instant now) {
        SettlementValues.require(SettlementValues.text(seller,100) && SettlementValues.microsecond(now),"판매자 잠금 입력이 올바르지 않습니다.");
        SellerLock result=new SellerLock(); result.sellerSubject=seller; result.createdAt=now; return result;
    }
}
