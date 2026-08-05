package com.stove.settlement.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerSettlementRepository extends JpaRepository<SellerSettlement, Long> {

    Optional<SellerSettlement> findBySellerIdAndSettlementMonth(Long sellerId, String settlementMonth);

    List<SellerSettlement> findBySettlementMonth(String settlementMonth);

    /**
     * 마감은 끝났는데 세금계산서가 아직 없는 확정본.
     *
     * <p>발행이 트랜잭션 밖이라 "확정본은 커밋됐고 발행은 실패한" 상태가 생길 수 있다.
     * 그 판매자는 원장이 이미 close 되어 <b>미마감 원장 기준으로는 재실행 대상에서 빠지므로</b>,
     * 여기서 따로 집어 주지 않으면 계산서 없는 확정본이 영구히 방치된다.
     */
    @Query("select s from SellerSettlement s "
            + "where s.settlementMonth = :month and s.taxInvoiceNo is null and s.netAmount > 0")
    List<SellerSettlement> findAwaitingTaxInvoice(@Param("month") String settlementMonth);
}
