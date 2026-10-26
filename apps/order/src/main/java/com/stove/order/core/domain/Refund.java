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
