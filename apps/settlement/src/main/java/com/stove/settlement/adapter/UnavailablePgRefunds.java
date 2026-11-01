package com.stove.settlement.adapter;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgRefund;
import com.stove.settlement.core.port.PgRefunds;

public class UnavailablePgRefunds implements PgRefunds {
    @Override public PgRefund require(PgPayment original,ConfirmedRefund confirmation) {
        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,"현재 모의 PG의 취소 조회 계약이 연결되지 않았습니다.");
    }
}
