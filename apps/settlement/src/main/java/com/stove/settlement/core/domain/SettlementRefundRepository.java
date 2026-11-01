package com.stove.settlement.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRefundRepository extends JpaRepository<SettlementRefund, Long> {
    @EntityGraph(attributePaths = "cancellations") Optional<SettlementRefund> findByRefundId(String refundId);
    @EntityGraph(attributePaths = "cancellations") Optional<SettlementRefund> findByOrderNo(String orderNo);
    List<SettlementRefund> findAllBySellerSubjectAndSettlementMonth(String sellerSubject, String settlementMonth);
    @Query("select r from SettlementRefund r where (:seller is null or r.sellerSubject=:seller) and (:month is null or r.settlementMonth=:month)")
    Page<SettlementRefund> search(@Param("seller") String seller, @Param("month") String month, Pageable pageable);
}
