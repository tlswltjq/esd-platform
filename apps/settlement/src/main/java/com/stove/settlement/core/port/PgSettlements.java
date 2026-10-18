package com.stove.settlement.core.port;

import com.stove.settlement.core.domain.PgSettlement;

public interface PgSettlements { PgSettlement require(String providerSettlementId); }
