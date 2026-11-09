package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.SellerCarryover;
import com.stove.settlement.core.domain.SellerCarryoverRepository;
import com.stove.settlement.core.domain.SellerLock;
import com.stove.settlement.core.domain.SellerLockRepository;
import com.stove.settlement.core.domain.SellerMonthBalance;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementMonth;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.domain.SettlementTotals;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class CloseSellerMonth {
    private final SellerMonthlySettlementRepository months;
    private final SettlementSaleRepository sales;
    private final Clock clock;
    private final SettlementRefundRepository refunds;
    private final SellerCarryoverRepository carryovers;
    private final SellerLockRepository sellers;
    private final TransactionTemplate transaction;
    public CloseSellerMonth(SellerMonthlySettlementRepository months,SettlementSaleRepository sales,Clock clock,PlatformTransactionManager transactions,
            SettlementRefundRepository refunds,SellerCarryoverRepository carryovers,SellerLockRepository sellers) {
        this.months=months; this.sales=sales; this.clock=clock; this.refunds=refunds; this.carryovers=carryovers; this.sellers=sellers;
        transaction=new TransactionTemplate(transactions); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public SellerMonthlySettlement execute(String seller,String month) {
        new SettlementMonth(month);
        if(seller==null || seller.isBlank() || seller.length()>100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        for(int attempt=0;attempt<5;attempt++) {
            try { return transaction.execute(tx -> close(seller,month)); }
            catch(DataIntegrityViolationException | TransientDataAccessException collision) { if(attempt==4) throw collision; }
        }
        throw new IllegalStateException("Unreachable retry state");
    }
    private SellerMonthlySettlement close(String seller,String month) {
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(month.compareTo(SettlementMonth.at(now).value())>=0) throw new BusinessException(ErrorCode.CONFLICT,"지난 월만 마감할 수 있습니다.");
        if(sellers.lock(seller).isEmpty()) sellers.saveAndFlush(SellerLock.of(seller,now));
        Optional<SellerMonthlySettlement> locked=months.lock(seller,month);
        // R3.4: a CLOSED month answers with its first result before the decision 130 order checks, which only guard OPEN -> CLOSED.
        if(locked.isPresent() && locked.get().isClosed()) return locked.get();
        // Decisions 42·130: a later CLOSED month already closed this one; closing it again would reuse that month's carry source.
        if(months.existsBySellerSubjectAndStatusAndSettlementMonthGreaterThan(seller,"CLOSED",month))
            throw new BusinessException(ErrorCode.CONFLICT,"이후 월이 이미 마감됐습니다.");
        if(months.existsBySellerSubjectAndStatusAndSettlementMonthLessThan(seller,"OPEN",month))
            throw new BusinessException(ErrorCode.CONFLICT,"이전 월을 먼저 마감해야 합니다.");
        SellerMonthlySettlement bucket=locked.orElseGet(() -> months.saveAndFlush(SellerMonthlySettlement.open(seller,month,now)));
        SettlementTotals total=SettlementTotals.empty();
        for(SettlementSale sale:sales.findAllBySellerSubjectAndSettlementMonth(seller,month)) total=total.plus(sale.totals());
        SettlementTotals reversed=SettlementTotals.empty();
        for(SettlementRefund refund:refunds.findAllBySellerSubjectAndSettlementMonth(seller,month)) reversed=reversed.plus(refund.totals());
        bucket.close(total,reversed,now); months.flush();
        carryovers.saveAndFlush(carry(bucket,now)); return bucket;
    }
    /** Decision 130: the carry comes from the latest earlier CLOSED month of the seller; months without rows are skipped. */
    private SellerCarryover carry(SellerMonthlySettlement closed,Instant now) {
        Optional<SellerMonthlySettlement> previous=months.findFirstBySellerSubjectAndStatusAndSettlementMonthLessThanOrderBySettlementMonthDesc(
                closed.getSellerSubject(),"CLOSED",closed.getSettlementMonth());
        if(previous.isEmpty()) return SellerCarryover.record(closed,null,0,now);
        SellerMonthlySettlement source=previous.get();
        SellerCarryover ledger=carryovers.findBySellerSubjectAndSettlementMonth(source.getSellerSubject(),source.getSettlementMonth()).orElse(null);
        return SellerCarryover.record(closed,source.getSettlementMonth(),SellerMonthBalance.of(source,ledger).carriedOut(),now);
    }
}
