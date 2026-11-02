package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgFunding;
import com.stove.settlement.core.domain.PgCostPosting;
import com.stove.settlement.core.domain.PgCostPostingRepository;
import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementCheck;
import com.stove.settlement.core.domain.PgStatementRowKey;
import com.stove.settlement.core.domain.PgStatementEntry;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgStatementItem;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementPurchase;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.PgTransactionRecord;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class ReconcilePgStatement {
    private final PgStatementRepository statements;
    private final PgStatementEntryRepository entries;
    private final PgStatementItemRepository items;
    private final PgTransactionRepository transactions;
    private final SettlementSaleRepository sales;
    private final SettlementRefundRepository refunds;
    private final Clock clock;
    private final PgCostPostingRepository postings;
    public ReconcilePgStatement(PgStatementRepository statements,PgStatementEntryRepository entries,PgStatementItemRepository items,
            PgTransactionRepository transactions,SettlementSaleRepository sales,SettlementRefundRepository refunds,Clock clock,PgCostPostingRepository postings) {
        this.statements=statements;this.entries=entries;this.items=items;this.transactions=transactions;this.sales=sales;this.refunds=refunds;this.clock=clock;this.postings=postings;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public PgStatement execute(Long id) {
        if(id==null || id<=0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        PgStatement statement=statements.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<PgStatementItem> rows=items.findByStatementIdOrderByItemPosition(id);
        if(rows.isEmpty()) {statement.finishCheck(0,0,0,clock.instant().truncatedTo(ChronoUnit.MICROS));return statement;}
        List<String> tokens=rows.stream().map(row -> row.getEntry().getPayToken()).distinct().toList();
        List<PgStatementEntry> related=entries.lockRelated(statement.getMerchantId(),statement.getEnvironment(),tokens);
        List<String> paymentIds=related.stream().map(PgStatementEntry::getOrderNo).distinct().toList();
        Map<String,SettlementSale> saleMap=sales.findByPaymentIdIn(paymentIds).stream().collect(Collectors.toMap(SettlementSale::getPaymentId,sale -> sale));
        Map<String,PgTransactionRecord> facts=transactions.findByPaymentIdIn(paymentIds).stream().collect(Collectors.toMap(PgTransactionRecord::getPaymentId,fact -> fact));
        List<String> orderNos=saleMap.values().stream().map(SettlementSale::getOrderNo).toList();
        Map<String,SettlementRefund> refundMap=orderNos.isEmpty()?Map.of():refunds.findByOrderNoIn(orderNos).stream().collect(Collectors.toMap(SettlementRefund::getOrderNo,refund -> refund));
        Map<PgStatementRowKey,Long> approvalCounts=new HashMap<>();Map<PgStatementRowKey,Long> refundAmounts=new HashMap<>();
        for(PgStatementEntry entry:related) {
            PgStatementRowKey key=new PgStatementRowKey(entry.getPayToken(),entry.getOrderNo());
            if("PAY".equals(entry.getTransactionType())) approvalCounts.merge(key,1L,Math::addExact);
            else refundAmounts.merge(key,Math.negateExact(entry.getAmount()),Math::addExact);
        }
        Map<Long,PgCostPosting> existing=postings.findByEntryIdIn(related.stream().map(PgStatementEntry::getId).toList()).stream()
                .collect(Collectors.toMap(posting -> posting.getEntry().getId(),posting -> posting));
        Map<Long,PgStatementCheck> checks=new HashMap<>();Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        // A later observation can complete older refund rows in the same payment group.
        for(PgStatementEntry entry:related) {
            SettlementSale sale=saleMap.get(entry.getOrderNo());PgTransactionRecord fact=facts.get(entry.getOrderNo());
            SettlementRefund refund=sale==null?null:refundMap.get(sale.getOrderNo());
            PgStatementRowKey key=new PgStatementRowKey(entry.getPayToken(),entry.getOrderNo());
            PgStatementCheck check=PgStatementCheck.compare(statement.scope(),entry.value(),purchase(sale,fact,refund),
                    approvalCounts.getOrDefault(key,0L),refundAmounts.getOrDefault(key,0L));
            checks.put(entry.getId(),check);
            PgCostPosting previous=existing.get(entry.getId());
            if(previous!=null) previous.check(check);
            if(!check.matched()) continue;
            PgCostPosting candidate=PgCostPosting.record(entry,sale,"PAY".equals(entry.getTransactionType())?null:refund,id,now);
            if(previous==null) postings.save(candidate);else previous.requireSame(candidate);
        }
        long matches=0;long pending=0;long mismatches=0;
        for(PgStatementItem item:rows) {
            PgStatementCheck check=checks.get(item.getEntry().getId());item.check(check,now);
            if(check.matched()) matches++;else if(check.mismatch()) mismatches++;else pending++;
        }
        postings.flush();statement.finishCheck(matches,pending,mismatches,now);items.flush();return statement;
    }
    private PgStatementPurchase purchase(SettlementSale sale,PgTransactionRecord fact,SettlementRefund refund) {
        if(sale==null || fact==null) return null;
        fact.requireSale(sale);PgFunding funding=fact.getFunding();
        return new PgStatementPurchase(sale.getPaymentId(),fact.getProvider(),fact.getProviderTransactionId(),
                funding==null?null:funding.getProviderStoreId(),funding==null?null:funding.getProviderChannelKey(),
                funding==null?null:funding.getProviderChannelType(),sale.getAmount(),refund==null?null:refund.getAmount(),
                refund==null?null:refund.getProviderTransactionId());
    }
}
