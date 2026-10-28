package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

public interface RefundCancellationHoldRepository extends JpaRepository<RefundCancellationHold, Long> {
    @Query("select h from RefundCancellationHold h, RefundExecution e, Payment p where h.attemptId=e.attemptId "
            + "and e.paymentId=p.paymentId and p.provider='PORTONE_TEST' "
            + "and (:activeOnly=false or (e.activeCancellationHoldId=h.holdId and e.status='PENDING' and e.refundConfirmedAt is null))")
    Page<RefundCancellationHold> findForAdministration(boolean activeOnly, Pageable page);
    Optional<RefundCancellationHold> findByHoldId(String holdId);
    Optional<RefundCancellationHold> findByAttemptIdAndClaimId(String attemptId, String claimId);
    Optional<RefundCancellationHold> findByResumeRequestId(String requestId);
}
