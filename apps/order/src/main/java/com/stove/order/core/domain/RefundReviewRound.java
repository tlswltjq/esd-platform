package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "order_refund_review_round") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundReviewRound {
    private static final Duration APPLICATION_WINDOW = Duration.ofDays(7);
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String reviewId;
    @Column(nullable = false, updatable = false, length = 36) private String refundId;
    @Column(nullable = false, updatable = false) private long reviewNo;
    @Column(unique = true, updatable = false, length = 36) private String applicationRequestId;
    @Column(unique = true, updatable = false, length = 36) private String previousReviewId;
    @Column(nullable = false, updatable = false, length = 100) private String buyerSubject;
    @Column(updatable = false, length = 500) private String customerReason;
    @Column(nullable = false, updatable = false) private Instant receivedAt;
    @Column(unique = true, length = 36) private String decisionRequestId;
    @Enumerated(EnumType.STRING) @Column(length = 20) private RefundReviewDecision decision;
    @Column(length = 100) private String operatorSubject;
    @Column(length = 500) private String decisionReason;
    private Instant decidedAt;
    @Column(unique=true, updatable=false, length=36) private String correctionRequestId;
    @Embedded private RefundApprovalStop approvalStop;

    public void stopApproval(RefundApprovalStopRequest input, String suspension, Instant at) {
        RefundReviewValues.requireTime(at);
        if (decision!=RefundReviewDecision.APPROVED || approvalStop!=null || !reviewId.equals(input.reviewId())
                || !refundId.equals(input.refundId()) || at.isBefore(decidedAt)) throw conflict();
        approvalStop=RefundApprovalStop.receive(input,suspension,at);
    }
    public static RefundReviewRound correctApproval(RefundReviewRound previous, Instant at) {
        RefundReviewValues.requireTime(at);
        if (previous==null || previous.approvalStop==null || !previous.approvalStop.canReopen()) throw conflict();
        RefundReviewRound next=new RefundReviewRound(); next.reviewId=UUID.randomUUID().toString(); next.refundId=previous.refundId;
        next.reviewNo=Math.addExact(previous.reviewNo,1); next.previousReviewId=previous.reviewId; next.buyerSubject=previous.buyerSubject;
        next.correctionRequestId=previous.approvalStop.getRequestId(); next.receivedAt=at;
        return next;
    }
    public String origin() { return reviewNo==1 ? "INITIAL" : correctionRequestId!=null ? "APPROVAL_CORRECTION" : "CUSTOMER_RECONSIDERATION"; }


    public static RefundReviewRound initial(Refund refund, String buyer) {
        if (refund == null || refund.isAutomaticRefundEligible()) throw conflict();
        RefundReviewValues.requireBuyer(buyer);
        RefundReviewRound round = new RefundReviewRound();
        round.reviewId = refund.getRefundId(); round.refundId = refund.getRefundId(); round.reviewNo = 1;
        round.buyerSubject = buyer; round.receivedAt = refund.getReceivedAt();
        RefundReviewReceipt first = refund.reviewReceipt();
        if (first != null) round.decide(new RefundReviewRequest(first.requestId(), first.refundId(), first.operatorSubject(), first.decision(), first.reason()), first.decidedAt());
        return round;
    }

    public static RefundReviewRound reconsider(Refund refund, RefundReviewRound previous, RefundReconsiderationRequest request, Instant at) {
        if (refund == null || previous == null || request == null || !refund.getRefundId().equals(previous.refundId)
                || !refund.getOrderNo().equals(request.orderNo()) || !previous.reviewId.equals(request.previousReviewId())
                || !previous.buyerSubject.equals(request.buyerSubject()) || !previous.canReconsiderAt(at)) throw conflict();
        RefundReviewRound round = new RefundReviewRound();
        round.reviewId = UUID.randomUUID().toString(); round.refundId = refund.getRefundId(); round.reviewNo = Math.addExact(previous.reviewNo, 1);
        round.applicationRequestId = request.requestId(); round.previousReviewId = previous.reviewId;
        round.buyerSubject = request.buyerSubject(); round.customerReason = request.reason(); round.receivedAt = at;
        return round;
    }

    public boolean canReconsiderAt(Instant at) {
        return decision == RefundReviewDecision.REJECTED && at != null && at.getNano() % 1000 == 0
                && !at.isBefore(decidedAt) && !at.isAfter(reconsiderationDeadline());
    }

    public Instant reconsiderationDeadline() { return decision == RefundReviewDecision.REJECTED ? decidedAt.plus(APPLICATION_WINDOW) : null; }

    public void requireSameApplication(Refund refund, RefundReconsiderationRequest request) {
        if (applicationRequestId == null || !applicationRequestId.equals(request.requestId()) || !refundId.equals(refund.getRefundId())
                || !previousReviewId.equals(request.previousReviewId()) || !buyerSubject.equals(request.buyerSubject())
                || !customerReason.equals(request.reason())) throw conflict();
    }

    public RefundReconsiderationReceipt applicationReceipt() {
        if (applicationRequestId == null) throw new IllegalStateException("The initial review is not a reconsideration application");
        return new RefundReconsiderationReceipt(applicationRequestId, refundId, reviewId, previousReviewId, reviewNo, receivedAt);
    }

    public boolean decide(RefundReviewRequest request, Instant at) {
        if (request == null || !refundId.equals(request.refundId())) throw conflict();
        if (decisionRequestId != null) {
            if (!decisionRequestId.equals(request.requestId()) || !operatorSubject.equals(request.operatorSubject())
                    || decision != request.decision() || !decisionReason.equals(request.reason())) throw conflict();
            return false;
        }
        RefundReviewValues.requireTime(at);
        if (at.isBefore(receivedAt)) throw conflict();
        decisionRequestId = request.requestId(); decision = request.decision(); operatorSubject = request.operatorSubject();
        decisionReason = request.reason(); decidedAt = at; return true;
    }

    public RefundReviewReceipt receipt() {
        return decisionRequestId == null ? null : new RefundReviewReceipt(decisionRequestId, refundId, operatorSubject, decision, decisionReason, decidedAt);
    }
    public RefundReviewRoundView view() {
        return new RefundReviewRoundView(reviewId, refundId, reviewNo, applicationRequestId, previousReviewId, customerReason, receivedAt, receipt(), origin(), correctionRequestId, approvalStop==null ? null : approvalStop.view(refundId,reviewId));
    }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "현재 반려 회차·재심사 요청·판정 근거가 다릅니다."); }
}
