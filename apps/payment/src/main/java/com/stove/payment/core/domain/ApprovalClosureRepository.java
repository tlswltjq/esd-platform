package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalClosureRepository extends JpaRepository<ApprovalClosure, Long> {
    Optional<ApprovalClosure> findByRequestId(String requestId);
    Optional<ApprovalClosure> findByApprovalId(String approvalId);
}
