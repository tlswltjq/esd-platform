package com.stove.settlement.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgStatementRepository extends JpaRepository<PgStatement,Long> {
    Optional<PgStatement> findByFingerprint(String fingerprint);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from PgStatement s where s.id=:id")
    Optional<PgStatement> lock(@Param("id") Long id);
    @Query("select count(distinct s.baseDate) from PgStatement s where s.merchantId=:merchant and s.environment=:environment and s.dateType='DUE' and s.baseDate>=:start and s.baseDate<:end")
    long collectedPayoutDays(@Param("merchant") String merchant,@Param("environment") String environment,@Param("start") LocalDate start,@Param("end") LocalDate end);
    @Query("select s from PgStatement s where s.id>:after and s.status in ('RECEIVED','PENDING') order by s.id")
    List<PgStatement> pendingAfter(@Param("after") long after,Pageable pageable);
}
