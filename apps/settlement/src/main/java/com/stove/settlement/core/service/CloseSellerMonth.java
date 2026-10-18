package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementMonth;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.domain.SettlementTotals;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class CloseSellerMonth {
    private final SellerMonthlySettlementRepository months;
    private final SettlementSaleRepository sales;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public CloseSellerMonth(SellerMonthlySettlementRepository months,SettlementSaleRepository sales,Clock clock,PlatformTransactionManager transactions) {
        this.months=months; this.sales=sales; this.clock=clock;
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
        SellerMonthlySettlement bucket=months.lock(seller,month).orElseGet(() -> months.saveAndFlush(SellerMonthlySettlement.open(seller,month,now)));
        if(bucket.isClosed()) return bucket;
        SettlementTotals total=SettlementTotals.empty();
        for(SettlementSale sale:sales.findAllBySellerSubjectAndSettlementMonth(seller,month)) total=total.plus(sale.totals());
        bucket.close(total,now); months.flush(); return bucket;
    }
}
