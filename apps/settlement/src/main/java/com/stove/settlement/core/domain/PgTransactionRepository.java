package com.stove.settlement.core.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgTransactionRepository extends JpaRepository<PgTransactionRecord, Long> {
    Optional<PgTransactionRecord> findByPaymentId(String paymentId);
    List<PgTransactionRecord> findByPaymentIdIn(List<String> paymentIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PgTransactionRecord p where p.paymentId=:paymentId")
    Optional<PgTransactionRecord> lock(@Param("paymentId") String paymentId);
}
