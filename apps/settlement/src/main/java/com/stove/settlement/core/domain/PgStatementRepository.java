package com.stove.settlement.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgStatementRepository extends JpaRepository<PgStatement,Long> {
    Optional<PgStatement> findByFingerprint(String fingerprint);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from PgStatement s where s.id=:id")
    Optional<PgStatement> lock(@Param("id") Long id);
}
