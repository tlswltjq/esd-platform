package com.stove.settlement.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {

    List<SettlementRecord> findByOrderNo(String orderNo);

    List<SettlementRecord> findByOrderNoAndRecordType(String orderNo, RecordType recordType);

    List<SettlementRecord> findBySettlementMonthAndClosedIsFalse(String settlementMonth);

    /**
     * 마감 대상 판매자 목록. 마감이 판매자 단위 트랜잭션으로 쪼개지므로
     * 오케스트레이터가 먼저 "누구를 돌 것인가"만 읽는다.
     */
    @Query("select distinct r.sellerId from SettlementRecord r "
            + "where r.settlementMonth = :month and r.closed = false order by r.sellerId")
    List<Long> findSellerIdsToClose(@Param("month") String settlementMonth);

    List<SettlementRecord> findBySettlementMonthAndSellerIdAndClosedIsFalse(
            String settlementMonth, Long sellerId);

    List<SettlementRecord> findBySellerIdAndSettlementMonth(Long sellerId, String settlementMonth);

    boolean existsByOrderNoAndProductIdAndRecordType(String orderNo, Long productId, RecordType recordType);
}
