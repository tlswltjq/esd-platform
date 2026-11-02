package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.PgCostPosting;
import com.stove.settlement.core.domain.PgCostPostingRepository;
import com.stove.settlement.core.domain.PgPayoutQuery;
import com.stove.settlement.core.domain.PgPayoutTotals;
import com.stove.settlement.core.domain.PgStatementEntry;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgStatementItem;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.SettlementQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReadPgPayouts {
    private final PgStatementEntryRepository entries;
    private final PgStatementRepository statements;
    private final PgCostPostingRepository postings;
    private final PgStatementItemRepository items;
    public Summary summary(PgPayoutQuery query) {
        PgPayoutTotals totals=entries.payoutTotals(query.merchantId(),query.environment(),query.start(),query.end());
        long days=statements.collectedPayoutDays(query.merchantId(),query.environment(),query.start(),query.end());
        String status=totals.recordCount()==0?(days==0?"NO_EVIDENCE":"EMPTY_REPORTED"):
                totals.linkedCount()==totals.recordCount()?"MATCHED_REPORTED":"PENDING_OR_MISMATCH";
        return new Summary(query.merchantId(),query.environment(),query.month(),"PG_REPORTED",status,days,totals.recordCount(),
                totals.linkedCount(),totals.amount(),totals.fee(),totals.vat(),totals.netAmount());
    }
    public Page<Item> items(PgPayoutQuery query,int page,int size) {
        new SettlementQuery(null,null,page,size);
        Page<PgStatementEntry> rows=entries.findByMerchantIdAndEnvironmentAndDueDateGreaterThanEqualAndDueDateLessThan(
                query.merchantId(),query.environment(),query.start(),query.end(),PageRequest.of(page,size,Sort.by("dueDate","id")));
        if(rows.isEmpty()) return rows.map(entry -> item(entry,null,null));
        List<Long> ids=rows.getContent().stream().map(PgStatementEntry::getId).toList();
        Map<Long,PgCostPosting> linked=postings.findByEntryIdIn(ids).stream().collect(Collectors.toMap(posting -> posting.getEntry().getId(),posting -> posting));
        Map<Long,PgStatementItem> checked=items.latestForEntries(ids).stream().collect(Collectors.toMap(item -> item.getEntry().getId(),item -> item));
        return rows.map(entry -> item(entry,linked.get(entry.getId()),checked.get(entry.getId())));
    }
    private Item item(PgStatementEntry entry,PgCostPosting posting,PgStatementItem checked) {
        String code=posting!=null?posting.getCheckCode():checked==null?"UNCHECKED":checked.getCheckCode();
        return new Item(entry.getId(),entry.getTransactionType(),entry.getTransactionId(),entry.getPayToken(),entry.getOrderNo(),
                entry.getDueDate(),entry.getAmount(),entry.getFee(),entry.getVat(),entry.value().netAmount(),code,
                posting==null?null:posting.getBusinessMonth(),posting==null?null:posting.getId(),
                posting==null?null:posting.getVerificationStatementId());
    }
    public record Summary(String merchantId,String environment,String month,String basis,String status,long collectedPayoutDayCount,
            long recordCount,long linkedCount,long reportedAmount,long reportedFee,long reportedVat,long reportedNetAmount) { }
    public record Item(Long sourceEntryId,String transactionType,String transactionId,String payToken,String paymentReference,
            LocalDate reportedPayoutDate,long reportedAmount,int reportedFee,int reportedVat,long reportedNetAmount,
            String checkCode,String businessMonth,Long costPostingId,Long verificationStatementId) { }
}
