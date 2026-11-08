package com.stove.payment.core.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

public interface RefundCancellationHoldRepository extends JpaRepository<RefundCancellationHold, Long> {
    String ADMINISTRATION_SCOPE = " from RefundCancellationHold h, RefundExecution e, Payment p where h.attemptId=e.attemptId "
            + "and e.paymentId=p.paymentId and p.provider='PORTONE_TEST' "
            + "and (:activeOnly=false or (e.activeCancellationHoldId=h.holdId and e.status='PENDING' and e.refundConfirmedAt is null))";

    /** List rows never load the full rejection type (up to 20,000,000 characters): the database cuts the preview (PR #168 R5). */
    @Query(value = "select new com.stove.payment.core.domain.RefundCancellationHoldSummary(h.holdId, h.attemptId, h.reason, "
            + "h.rejectionStatus, substring(h.rejectionType, 1, " + RefundCancellationHoldSummary.PREVIEW_LENGTH + "), "
            + "character_length(h.rejectionType), h.observedAt, h.activated, h.resumeRequestId, h.resumedAt, h.operatorSubject, h.resumeNote)"
            + ADMINISTRATION_SCOPE, countQuery = "select count(h)" + ADMINISTRATION_SCOPE)
    Page<RefundCancellationHoldSummary> findForAdministration(boolean activeOnly, Pageable page);
    Optional<RefundCancellationHold> findByHoldId(String holdId);
    Optional<RefundCancellationHold> findByAttemptIdAndClaimId(String attemptId, String claimId);
    Optional<RefundCancellationHold> findByResumeRequestId(String requestId);
}
