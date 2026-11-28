package com.stove.settlement.adapter;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgRefund;
import com.stove.settlement.core.port.PgRefunds;

/** PG 연동을 미룬 동안(결정 remove-pg-adapters) 취소 근거를 조회할 곳이 없다. 취소 근거를 지어내지 않고 거절한다. */
public class UnavailablePgRefunds implements PgRefunds {
    @Override public PgRefund require(PgPayment original,ConfirmedRefund confirmation) {
        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,"PG 취소 조회가 연결되지 않았습니다.");
    }
}
