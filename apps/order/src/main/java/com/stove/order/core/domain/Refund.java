package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {
    private static final RefundPolicy REFUND_POLICY = new RefundPolicy();

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String refundId;
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String orderNo;
    @Column(nullable = false, updatable = false)
    private Instant receivedAt;
    @Column(nullable = false, updatable = false)
    private boolean automaticRefundEligible;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40)
    private RefundStatus status;
    @Column(unique = true, length = 64)
    private String suspensionRequestEventId;
    @Column(unique = true, length = 36)
    private String currentAttemptId;
    @Column(unique = true, length = 64)
    private String paymentConfirmationEventId;
    private Instant completedAt;
    @Column(length = 64)
    private String paymentConfirmationFingerprint;
    @Column(length = 30)
    private String paymentConfirmationSource;

    public boolean recordFailure(Order order, RefundAttempt attempt, RefundFailureConfirmation input, Instant at) {
        if (input == null) throw new RefundCompletionRejected("실패 확인이 필요합니다.");
        requireRestorationPurchase(order, attempt, input.refundId(), input.attemptId(), input.orderNo(), input.paymentId(), input.buyerSubject());
        if (!input.requestEventId().equals(attempt.getExecutionRequestEventId()) || input.amount() != order.getAmount()
                || !input.currency().equals(order.getCurrency()) || input.occurredAt().isBefore(attempt.getStartedAt()))
            throw new RefundCompletionRejected("실패 확인의 요청·금액이 다릅니다.");
        if (attempt.getFailureEventId() != null) {
            if (!input.fingerprint().equals(attempt.getFailureFingerprint())) throw new RefundCompletionRejected("확정한 실패 근거가 다릅니다.");
            return false;
        }
        if (status != RefundStatus.PENDING_PAYMENT_REFUND || paymentConfirmationEventId != null)
            throw new RefundCompletionRejected("실패 확인을 기다리는 환불이 아닙니다.");
        attempt.recordRefundFailure(input, at); status = RefundStatus.PENDING_LICENSE_RESTORATION;
        return true;
    }

    public boolean confirmRestoration(Order order, RefundAttempt attempt, LicenseRestorationConfirmation input) {
        if (input == null) throw new RefundCompletionRejected("복구 완료가 필요합니다.");
        requireRestorationPurchase(order, attempt, input.refundId(), input.attemptId(), input.orderNo(), input.paymentId(), input.buyerSubject());
        if ((status != RefundStatus.PENDING_LICENSE_RESTORATION && status != RefundStatus.FAILED)
                || !order.getProductId().equals(input.productId()) || !order.getProductCode().equals(input.productCode()))
            throw new RefundCompletionRejected("복구 완료가 현재 복구 대기와 다릅니다.");
        boolean changed = attempt.recordRestoration(input);
        if (changed) status = RefundStatus.FAILED;
        return changed;
    }

    private void requireRestorationPurchase(Order order, RefundAttempt attempt, String refund, String execution, String orderId, String payment, String buyer) {
        if (order == null || attempt == null || order.getStatus() != OrderStatus.PAID || !refundId.equals(refund) || !orderNo.equals(orderId)
                || !orderNo.equals(order.getOrderNo()) || !payment.equals(order.getPaymentId()) || !buyer.equals(order.getBuyerSubject())
                || !execution.equals(currentAttemptId) || !execution.equals(attempt.getAttemptId()) || !refundId.equals(attempt.getRefundId()))
            throw new RefundCompletionRejected("실패·복구 근거가 현재 주문·환불·시도와 다릅니다.");
    }

    public boolean recordExecutionSuccess(Order order, RefundAttempt attempt, RefundExecutionSuccess input) {
        if (input == null) throw new RefundCompletionRejected("실행 성공 근거가 필요합니다.");
        verifyPaymentIdentity(order, attempt, input.execution());
        if ((status != RefundStatus.PENDING_PAYMENT_REFUND && status != RefundStatus.COMPLETED)
                || (paymentConfirmationEventId != null && !paymentConfirmationEventId.equals(input.confirmationEventId())))
            throw new RefundCompletionRejected("실행 성공과 최초 환불 완료의 연결이 다릅니다.");
        return attempt.recordCancellationSuccess(input);
    }

    public boolean confirmPayment(Order order, RefundAttempt attempt, RefundPaymentConfirmation input) {
        verifyPaymentIdentity(order, attempt, input);
        if (attempt.getSuccessConfirmationEventId() != null && (!attempt.getSuccessConfirmationEventId().equals(input.eventId())
                || input.cancellationIds().size() != 1 || !input.cancellationIds().get(0).equals(attempt.getSuccessfulCancellationId())))
            throw new RefundCompletionRejected("성공한 실행과 환불 완료의 취소 근거가 다릅니다.");
        if (status == RefundStatus.COMPLETED) {
            if (!input.fingerprint().equals(paymentConfirmationFingerprint))
                throw new RefundCompletionRejected("이미 확정한 환불 완료 근거와 다릅니다.");
            return false;
        }
        if (status != RefundStatus.PENDING_PAYMENT_REFUND
                || (attempt.getStatus() != RefundAttemptStatus.PENDING && attempt.getStatus() != RefundAttemptStatus.SUCCEEDED))
            throw new RefundCompletionRejected("전액 취소 확인을 기다리는 환불이 아닙니다.");
        if ("PG_RESPONSE".equals(input.source())) attempt.recordCancellationSuccess(new RefundExecutionSuccess(input, input.eventId()));
        else if (attempt.getStatus() == RefundAttemptStatus.PENDING) attempt.requireReconciliation();
        status = RefundStatus.COMPLETED;
        paymentConfirmationEventId = input.eventId(); completedAt = input.occurredAt();
        paymentConfirmationFingerprint = input.fingerprint(); paymentConfirmationSource = input.source();
        return true;
    }

    private void verifyPaymentIdentity(Order order, RefundAttempt attempt, RefundPaymentConfirmation input) {
        if (order == null || attempt == null || input == null || order.getStatus() != OrderStatus.PAID
                || !refundId.equals(input.refundId()) || !orderNo.equals(input.orderNo()) || !orderNo.equals(order.getOrderNo())
                || !input.paymentId().equals(order.getPaymentId()) || !input.buyerSubject().equals(order.getBuyerSubject())
                || input.amount() != order.getAmount() || !input.currency().equals(order.getCurrency())
                || !input.attemptId().equals(currentAttemptId) || !input.attemptId().equals(attempt.getAttemptId())
                || !refundId.equals(attempt.getRefundId()) || !input.requestEventId().equals(attempt.getExecutionRequestEventId())
                || input.occurredAt().isBefore(attempt.getStartedAt()))
            throw new RefundCompletionRejected("전액 취소 확인이 현재 주문·환불·실행 요청과 다릅니다.");
    }

    public void verifySuspensionRequest(LicenseSuspensionConfirmation input) {
        if (input == null || suspensionRequestEventId == null
                || !suspensionRequestEventId.equals(input.requestEventId())
                || !refundId.equals(input.refundId()) || !orderNo.equals(input.orderNo())) {
            throw new RefundCompletionRejected("정지 완료에 대응하는 환불·정지 요청이 없습니다.");
        }
    }

    public void recordExecution(RefundAttempt attempt) {
        if (status != RefundStatus.PENDING_LICENSE_SUSPENSION || currentAttemptId != null || attempt == null
                || !refundId.equals(attempt.getRefundId()) || attempt.getAttemptNo() != 1
                || attempt.getStatus() != RefundAttemptStatus.PENDING || suspensionRequestEventId == null
                || !suspensionRequestEventId.equals(attempt.getSuspensionRequestEventId())
                || attempt.getSuspensionEventId() == null || attempt.getExecutionRequestEventId() == null) {
            throw new RefundCompletionRejected("현재 환불 단계에서 첫 실행을 시작할 수 없습니다.");
        }
        currentAttemptId = attempt.getAttemptId();
        status = RefundStatus.PENDING_PAYMENT_REFUND;
    }

    public static Refund request(Order order, Instant receivedAt) {
        requirePaidOrder(order);
        requireReceiptTime(receivedAt);

        Refund refund = new Refund();
        refund.refundId = UUID.randomUUID().toString();
        refund.orderNo = order.getOrderNo();
        refund.receivedAt = receivedAt;
        refund.automaticRefundEligible = REFUND_POLICY.isWithinAutomaticRefundWindow(
                order.getResultOccurredAt(), receivedAt);
        refund.status = refund.automaticRefundEligible
                ? RefundStatus.PENDING_LICENSE_GRANT : RefundStatus.PENDING_REVIEW;
        return refund;
    }

    private static void requirePaidOrder(Order order) {
        if (order == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "환불할 주문이 필요합니다.");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제가 완료된 주문만 환불을 요청할 수 있습니다.");
        }
    }

    public boolean confirmLicenseGrant(LicenseGrant grant) {
        if (grant == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지급 확인 근거가 필요합니다.");
        }
        if (!orderNo.equals(grant.orderNo())) {
            throw new BusinessException(ErrorCode.CONFLICT, "다른 주문의 지급 확인입니다.");
        }
        if (status != RefundStatus.PENDING_LICENSE_GRANT) {
            return false;
        }
        status = RefundStatus.PENDING_LICENSE_SUSPENSION;
        suspensionRequestEventId = UUID.randomUUID().toString();
        return true;
    }

    private static void requireReceiptTime(Instant receivedAt) {
        if (receivedAt == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "환불 접수 시각이 필요합니다.");
        }
    }
}
