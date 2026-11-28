package com.stove.settlement.adapter;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.port.PgStatementSource;

/** PG 연동을 미룬 동안(결정 remove-pg-adapters) 정산 자료 출처가 없다. 빈 정산 자료를 만들어 반환하지 않는다. */
public class UnavailablePgStatementSource implements PgStatementSource {
    @Override public PgStatementReport fetch(PgStatementQuery query) {
        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,"PG 정산 자료 조회가 연결되지 않았습니다.");
    }
}
