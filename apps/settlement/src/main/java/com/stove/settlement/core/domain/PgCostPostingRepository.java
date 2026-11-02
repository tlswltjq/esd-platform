package com.stove.settlement.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgCostPostingRepository extends JpaRepository<PgCostPosting,Long> {
    Optional<PgCostPosting> findByEntryId(Long entryId);
    List<PgCostPosting> findByEntryIdIn(List<Long> entryIds);
    @Query("""
            select new com.stove.settlement.core.domain.PgPostedCostGroup(p.sellerSubject,p.businessMonth,e.transactionType,
                count(distinct p.saleId),count(distinct p.refundRecordId),sum(e.fee),sum(e.vat))
            from PgCostPosting p join p.entry e where p.checkCode='MATCHED_REPORTED' and p.sellerSubject in :sellers and p.businessMonth in :months
            group by p.sellerSubject,p.businessMonth,e.transactionType
            """)
    List<PgPostedCostGroup> summarize(@Param("sellers") List<String> sellers,@Param("months") List<String> months);
}
