package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundInvestigationRepository extends JpaRepository<RefundInvestigation, Long> {
    Optional<RefundInvestigation> findByRequestId(String requestId);
    Page<RefundInvestigation> findByAttemptId(String attemptId, Pageable page);
}
