package com.stove.settlement.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgReceiptRepository extends JpaRepository<PgReceipt,Long> {
    Optional<PgReceipt> findByProviderAndProviderSettlementId(String provider,String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PgReceipt r where r.provider=:provider and r.providerSettlementId=:id")
    Optional<PgReceipt> lock(@Param("provider") String provider,@Param("id") String id);
    @Query("select r from PgReceipt r where (:status is null or r.status=:status)")
    Page<PgReceipt> search(@Param("status") String status,Pageable pageable);
}
