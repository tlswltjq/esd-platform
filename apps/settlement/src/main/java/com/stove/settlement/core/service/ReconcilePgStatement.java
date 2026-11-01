package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgFunding;
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
    public ReconcilePgStatement(PgStatementRepository statements,PgStatementEntryRepository entries,PgStatementItemRepository items,
            PgTransactionRepository transactions,SettlementSaleRepository sales,SettlementRefundRepository refunds,Clock clock) {
        this.statements=statements;this.entries=entries;this.items=items;this.transactions=transactions;this.sales=sales;this.refunds=refunds;this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public PgStatement execute(Long id) {
        if(id==null || id<=0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        PgStatement statement=statements.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<PgStatementItem> rows=items.findByStatementIdOrderByItemPosition(id);
        if(rows.isEmpty()) {statement.finishCheck(0,0,0,clock.instant().truncatedTo(ChronoUnit.MICROS));return statement;}
        List<String> paymentIds=rows.stream().map(row -> row.getEntry().getOrderNo()).distinct().toList();
        Map<String,SettlementSale> saleMap=sales.findByPaymentIdIn(paymentIds).stream().collect(Collectors.toMap(SettlementSale::getPaymentId,sale -> sale));
        Map<String,PgTransactionRecord> facts=transactions.findByPaymentIdIn(paymentIds).stream().collect(Collectors.toMap(PgTransactionRecord::getPaymentId,fact -> fact));
        List<String> orderNos=saleMap.values().stream().map(SettlementSale::getOrderNo).toList();
        Map<String,SettlementRefund> refundMap=orderNos.isEmpty()?Map.of():refunds.findByOrderNoIn(orderNos).stream().collect(Collectors.toMap(SettlementRefund::getOrderNo,refund -> refund));
        List<String> tokens=rows.stream().map(row -> row.getEntry().getPayToken()).distinct().toList();
        Map<PgStatementRowKey,Long> approvalCounts=new HashMap<>();Map<PgStatementRowKey,Long> refundAmounts=new HashMap<>();
        for(PgStatementEntry entry:entries.findByMerchantIdAndEnvironmentAndPayTokenIn(statement.getMerchantId(),statement.getEnvironment(),tokens)) {
            PgStatementRowKey key=new PgStatementRowKey(entry.getPayToken(),entry.getOrderNo());
            if("PAY".equals(entry.getTransactionType())) approvalCounts.merge(key,1L,Math::addExact);
            else refundAmounts.merge(key,Math.negateExact(entry.getAmount()),Math::addExact);
        }
        long matches=0;long pending=0;long mismatches=0;Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        for(PgStatementItem item:rows) {
            PgStatementEntry entry=item.getEntry();String paymentId=entry.getOrderNo();SettlementSale sale=saleMap.get(paymentId);
            PgTransactionRecord fact=facts.get(paymentId);PgStatementPurchase purchase=null;
            if(sale!=null && fact!=null) {
                fact.requireSale(sale);PgFunding funding=fact.getFunding();SettlementRefund refund=refundMap.get(sale.getOrderNo());
                purchase=new PgStatementPurchase(paymentId,fact.getProvider(),fact.getProviderTransactionId(),funding==null?null:funding.getProviderStoreId(),
                        funding==null?null:funding.getProviderChannelKey(),funding==null?null:funding.getProviderChannelType(),sale.getAmount(),
                        refund==null?null:refund.getAmount(),refund==null?null:refund.getProviderTransactionId());
            }
            PgStatementRowKey key=new PgStatementRowKey(entry.getPayToken(),entry.getOrderNo());
            PgStatementCheck check=PgStatementCheck.compare(statement.scope(),entry.value(),purchase,approvalCounts.getOrDefault(key,0L),refundAmounts.getOrDefault(key,0L));
            item.check(check,now);if(check.matched()) matches++;else if(check.mismatch()) mismatches++;else pending++;
        }
        statement.finishCheck(matches,pending,mismatches,now);items.flush();return statement;
    }
}
