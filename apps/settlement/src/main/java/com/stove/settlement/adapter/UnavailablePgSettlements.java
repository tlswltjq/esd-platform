package com.stove.settlement.adapter;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgSettlement;
import com.stove.settlement.core.port.PgSettlements;

/** PG 연동을 미룬 동안(결정 remove-pg-adapters) 지급 명세 출처가 없다. 빈 명세·모의 비용을 만들어 반환하지 않는다. */
public class UnavailablePgSettlements implements PgSettlements {
    @Override public PgSettlement require(String providerSettlementId) {
        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,"PG 지급 명세 조회가 연결되지 않았습니다.");
    }
}
