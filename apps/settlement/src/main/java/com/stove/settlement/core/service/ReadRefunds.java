package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.SettlementQuery;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ReadRefunds {
    private final SettlementRefundRepository refunds;
    public Page<SettlementRefund> execute(SettlementQuery query) {
        return refunds.search(query.sellerSubject(),query.month(),PageRequest.of(query.page(),query.size(),Sort.by("confirmedAt","id").descending()));
    }
    public SettlementRefund execute(String refundId) {
        if(refundId==null || !refundId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return refunds.findByRefundId(refundId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
