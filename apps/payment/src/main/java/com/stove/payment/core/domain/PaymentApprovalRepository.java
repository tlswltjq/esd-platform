package com.stove.payment.core.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentApprovalRepository extends JpaRepository<PaymentApproval, Long> {
    @Query("select a from PaymentApproval a where a.status=com.stove.payment.core.domain.PaymentApprovalStatus.REVIEW_REQUIRED order by a.receivedAt,a.id")
    Page<PaymentApproval> findReviews(Pageable page);
    @Query("select a from PaymentApproval a where a.status=com.stove.payment.core.domain.PaymentApprovalStatus.REVIEW_REQUIRED and (a.lastErrorCode=:reason or (:reason='OTHER' and (a.lastErrorCode is null or a.lastErrorCode not in :known))) order by a.receivedAt,a.id")
    Page<PaymentApproval> findReviewsByReason(String reason, List<String> known, Pageable page);
    long countByStatusAndLastErrorCode(PaymentApprovalStatus status, String reason);
    long countByStatus(PaymentApprovalStatus status);
    Optional<PaymentApproval> findByApprovalId(String approvalId);
    Optional<PaymentApproval> findBySupplierAttemptId(String supplierAttemptId);
    Optional<PaymentApproval> findFirstByPaymentIdAndStatusInOrderByIdDesc(String paymentId, List<PaymentApprovalStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentApproval a where a.approvalId=:approvalId")
    Optional<PaymentApproval> lockByApprovalId(String approvalId);
    @Query("select a.approvalId from PaymentApproval a where a.nextAt<=:now and (a.leaseUntil is null or a.leaseUntil<=:now) order by a.nextAt,a.id")
    List<String> due(Instant now, Pageable pageable);
}
