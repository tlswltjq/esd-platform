package com.stove.settlement.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementSaleRepository extends JpaRepository<SettlementSale,Long> {
    Optional<SettlementSale> findByOrderNo(String orderNo);
    List<SettlementSale> findAllBySellerSubjectAndSettlementMonth(String sellerSubject,String settlementMonth);
    @Query("select s from SettlementSale s where (:seller is null or s.sellerSubject=:seller) and (:month is null or s.settlementMonth=:month)")
    Page<SettlementSale> search(@Param("seller") String seller,@Param("month") String month,Pageable pageable);
}
