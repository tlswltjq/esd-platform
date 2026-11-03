package com.stove.catalog.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogRequestGateRepository extends JpaRepository<CatalogRequestGate, Long> {
    @Modifying
    @Query(value = "INSERT INTO catalog_request_gate(request_id) VALUES (:id) "
            + "ON DUPLICATE KEY UPDATE request_id=VALUES(request_id)", nativeQuery = true)
    void ensure(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from CatalogRequestGate g where g.requestId=:id")
    Optional<CatalogRequestGate> lock(@Param("id") Long id);
}
