package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgReceipt;
import com.stove.settlement.core.domain.PgReceiptRepository;
import com.stove.settlement.core.domain.PgReceiptView;
import com.stove.settlement.core.domain.PgSettlementId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class ReadPgReceipts {
    private final PgReceiptRepository receipts;
    public ReadPgReceipts(PgReceiptRepository receipts) { this.receipts=receipts; }
    public PgReceiptView execute(String id) {
        new PgSettlementId(id);
        return receipts.findByProviderAndProviderSettlementId("SIMULATED",id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).view();
    }
    public Page<PgReceiptView> execute(String status,int page,int size) {
        if(page<0 || size<1 || size>100 || status!=null && !status.equals("REPORTED_PAID") && !status.equals("MISMATCH") && !status.equals("SIMULATED_RECEIVED"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return receipts.search(status,PageRequest.of(page,size,Sort.by("reportedAt","id").descending())).map(PgReceipt::view);
    }
}
