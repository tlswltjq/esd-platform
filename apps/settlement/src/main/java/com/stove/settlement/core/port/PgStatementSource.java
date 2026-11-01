package com.stove.settlement.core.port;

import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementReport;

public interface PgStatementSource {
    PgStatementReport fetch(PgStatementQuery query);
}
