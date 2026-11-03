package com.stove.review.core.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRequestGateRepository extends JpaRepository<ReviewRequestGate, Long> {
    @Modifying
    @Query(value = "INSERT INTO review_request_gate(request_id) VALUES (:id) "
            + "ON DUPLICATE KEY UPDATE request_id=VALUES(request_id)", nativeQuery = true)
    void ensure(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from ReviewRequestGate g where g.requestId=:id")
    Optional<ReviewRequestGate> lock(@Param("id") Long id);
}
