package com.stove.settlement.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {

    List<SettlementRecord> findByOrderNo(String orderNo);

    List<SettlementRecord> findByOrderNoAndRecordType(String orderNo, RecordType recordType);

    List<SettlementRecord> findBySettlementMonthAndClosedIsFalse(String settlementMonth);

    List<SettlementRecord> findBySellerIdAndSettlementMonth(Long sellerId, String settlementMonth);

    boolean existsByOrderNoAndProductIdAndRecordType(String orderNo, Long productId, RecordType recordType);
}
