package com.stove.settlement.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgFeeRepository extends JpaRepository<PgFeeRecord, Long> {
    Optional<PgFeeRecord> findByPaymentId(String paymentId);
    List<PgFeeRecord> findByPaymentIdIn(List<String> paymentIds);
    @Query("""
            select new com.stove.settlement.core.domain.PgFeeGroup(s.sellerSubject,s.settlementMonth,
                count(s.id),count(f.id),coalesce(sum(f.cost.pgFeeAmount),0))
            from SettlementSale s left join PgFeeRecord f on f.paymentId=s.paymentId
            where s.sellerSubject in :sellers and s.settlementMonth in :months
            group by s.sellerSubject,s.settlementMonth
            """)
    List<PgFeeGroup> summarize(@Param("sellers") List<String> sellers, @Param("months") List<String> months);
}
