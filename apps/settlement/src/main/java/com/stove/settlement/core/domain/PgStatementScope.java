package com.stove.settlement.core.domain;

/** Credential scope and explicitly verified identity mapping, never a credential value. */
public record PgStatementScope(String merchantId,String environment,String portoneStoreId,String portoneChannelKey,
        boolean identityMappingVerified) {
    public PgStatementScope {
        SettlementValues.require(SettlementValues.text(merchantId,100) && ("TEST".equals(environment) || "LIVE".equals(environment))
                && SettlementValues.text(portoneStoreId,100) && SettlementValues.text(portoneChannelKey,100)
                && (!"TEST".equals(environment) || !identityMappingVerified),
                "정산 자료의 가맹점·환경·상점/채널 범위가 필요합니다.");
    }
}
