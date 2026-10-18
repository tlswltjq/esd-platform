package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgReceipt;
import com.stove.settlement.core.domain.PgReceiptRepository;
import com.stove.settlement.core.domain.PgReceiptView;
import com.stove.settlement.core.domain.PgSettlement;
import com.stove.settlement.core.domain.PgSettlementId;
import com.stove.settlement.core.port.PgSettlements;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ReceivePgSettlement {
    private final PgReceiptRepository receipts;
    private final PgSettlements supplier;
    private final ReconcilePgReceipt reconcile;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public ReceivePgSettlement(PgReceiptRepository receipts,PgSettlements supplier,ReconcilePgReceipt reconcile,Clock clock,PlatformTransactionManager transactions) {
        this.receipts=receipts; this.supplier=supplier; this.reconcile=reconcile; this.clock=clock;
        transaction=new TransactionTemplate(transactions); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public PgReceiptView execute(String id,String operator) {
        new PgSettlementId(id);
        if(operator==null || operator.isBlank() || operator.length()>100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("PG 조회 중 업무 트랜잭션을 유지할 수 없습니다.");
        if(receipts.findByProviderAndProviderSettlementId("SIMULATED",id).isPresent()) return reconcile.execute(id);
        PgSettlement report=supplier.require(id);
        if(!id.equals(report.providerSettlementId())) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        try { transaction.executeWithoutResult(tx -> store(report,operator)); }
        catch(DataIntegrityViolationException collision) {
            transaction.executeWithoutResult(tx -> {
                PgReceipt existing=receipts.findByProviderAndProviderSettlementId("SIMULATED",id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,"다른 명세에 접수된 PG 거래입니다."));
                existing.requireSame(report);
            });
        }
        return reconcile.execute(id);
    }
    private void store(PgSettlement report,String operator) {
        PgReceipt existing=receipts.findByProviderAndProviderSettlementId("SIMULATED",report.providerSettlementId()).orElse(null);
        if(existing!=null) { existing.requireSame(report); return; }
        receipts.saveAndFlush(PgReceipt.receive(report,operator,clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
}
