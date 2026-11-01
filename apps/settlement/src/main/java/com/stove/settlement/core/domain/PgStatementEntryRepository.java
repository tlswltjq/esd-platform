package com.stove.settlement.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PgStatementEntryRepository extends JpaRepository<PgStatementEntry,Long> {
    Optional<PgStatementEntry> findByMerchantIdAndEnvironmentAndTransactionId(String merchantId,String environment,String transactionId);
    List<PgStatementEntry> findByMerchantIdAndEnvironmentAndPayTokenIn(String merchantId,String environment,List<String> tokens);
}
