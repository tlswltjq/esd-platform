package com.stove.settlement.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerMonthlySettlementRepository extends JpaRepository<SellerMonthlySettlement,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from SellerMonthlySettlement m where m.sellerSubject=:seller and m.settlementMonth=:month")
    Optional<SellerMonthlySettlement> lock(@Param("seller") String seller,@Param("month") String month);
    @Query("select m from SellerMonthlySettlement m where (:seller is null or m.sellerSubject=:seller) and (:month is null or m.settlementMonth=:month)")
    Page<SellerMonthlySettlement> search(@Param("seller") String seller,@Param("month") String month,Pageable pageable);
}
