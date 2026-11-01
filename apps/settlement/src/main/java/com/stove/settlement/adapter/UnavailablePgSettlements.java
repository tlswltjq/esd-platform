package com.stove.settlement.adapter;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgSettlement;
import com.stove.settlement.core.port.PgSettlements;

/** TEST 승인에는 실제 지급 명세가 없다. 빈 명세·모의 비용을 만들어 반환하지 않는다. */
public class UnavailablePgSettlements implements PgSettlements {
    @Override public PgSettlement require(String providerSettlementId) {
        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "현재 공급자의 지급 명세 출처가 연결되지 않았습니다.");
    }
}
