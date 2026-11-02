package com.stove.settlement.core.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PgStatementEntryRepository extends JpaRepository<PgStatementEntry,Long> {
    Optional<PgStatementEntry> findByMerchantIdAndEnvironmentAndTransactionId(String merchantId,String environment,String transactionId);
    List<PgStatementEntry> findByMerchantIdAndEnvironmentAndPayTokenIn(String merchantId,String environment,List<String> tokens);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PgStatementEntry e where e.merchantId=:merchant and e.environment=:environment and e.payToken in :tokens order by e.id")
    List<PgStatementEntry> lockRelated(@Param("merchant") String merchant,@Param("environment") String environment,@Param("tokens") List<String> tokens);
    Page<PgStatementEntry> findByMerchantIdAndEnvironmentAndDueDateGreaterThanEqualAndDueDateLessThan(
            String merchant,String environment,LocalDate start,LocalDate end,Pageable pageable);
    @Query("""
            select new com.stove.settlement.core.domain.PgPayoutTotals(count(e.id),count(p.id),
                coalesce(sum(e.amount),0),coalesce(sum(e.fee),0),coalesce(sum(e.vat),0))
            from PgStatementEntry e left join PgCostPosting p on p.entry=e and p.checkCode='MATCHED_REPORTED'
            where e.merchantId=:merchant and e.environment=:environment and e.dueDate>=:start and e.dueDate<:end
            """)
    PgPayoutTotals payoutTotals(@Param("merchant") String merchant,@Param("environment") String environment,
            @Param("start") LocalDate start,@Param("end") LocalDate end);
}
