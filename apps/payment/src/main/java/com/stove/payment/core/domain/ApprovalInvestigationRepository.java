package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalInvestigationRepository extends JpaRepository<ApprovalInvestigation, Long> {
    long countByApprovalId(String approvalId);
    Optional<ApprovalInvestigation> findByRequestId(String requestId);
    Page<ApprovalInvestigation> findByApprovalId(String approvalId, Pageable page);
}
