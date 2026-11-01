package com.stove.settlement.adapter;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.port.PgStatementSource;

public class UnavailablePgStatementSource implements PgStatementSource {
    @Override public PgStatementReport fetch(PgStatementQuery query) {
        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,"토스페이 정산 자료 조회가 설정되지 않았습니다.");
    }
}
