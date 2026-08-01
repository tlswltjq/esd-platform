package com.stove.settlement.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerSettlementRepository extends JpaRepository<SellerSettlement, Long> {

    Optional<SellerSettlement> findBySellerIdAndSettlementMonth(Long sellerId, String settlementMonth);

    List<SellerSettlement> findBySettlementMonth(String settlementMonth);
}
