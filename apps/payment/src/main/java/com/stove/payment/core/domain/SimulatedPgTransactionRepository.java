package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SimulatedPgTransactionRepository extends JpaRepository<SimulatedPgTransaction, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SimulatedPgTransaction t where t.pgTxId = :pgTxId")
    Optional<SimulatedPgTransaction> findForUpdate(@Param("pgTxId") String pgTxId);
}
