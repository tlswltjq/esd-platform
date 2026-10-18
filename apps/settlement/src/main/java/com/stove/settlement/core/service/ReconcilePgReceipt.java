package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgReceipt;
import com.stove.settlement.core.domain.PgReceiptItem;
import com.stove.settlement.core.domain.PgReceiptRepository;
import com.stove.settlement.core.domain.PgReceiptView;
import com.stove.settlement.core.domain.PgSettlementId;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class ReconcilePgReceipt {
    private final PgReceiptRepository receipts;
    private final SettlementSaleRepository sales;
    private final Clock clock;
    public ReconcilePgReceipt(PgReceiptRepository receipts,SettlementSaleRepository sales,Clock clock) {
        this.receipts=receipts; this.sales=sales; this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public PgReceiptView execute(String id) {
        new PgSettlementId(id);
        PgReceipt receipt=receipts.lock("SIMULATED",id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if(!receipt.isReceived()) {
            for(PgReceiptItem item:receipt.getItems()) item.compare(sales.findByOrderNo(item.getOrderNo()).orElse(null));
            receipt.finishCheck(clock.instant().truncatedTo(ChronoUnit.MICROS));
            receipts.flush();
        }
        return receipt.view();
    }
}
