package com.stove.payment.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReceiveRefundExecution {
    public static final String CONSUMER_GROUP = "payment-refund-execution";
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final ProcessedEventGuard inbox;
    private final RefundExecutionTraceContexts traceContexts;
    private final Clock clock;

    public RefundExecution execute(RefundExecutionRequest request) {
        return execute(request, PaymentTraceContext.NONE);
    }

    public RefundExecution execute(RefundExecutionRequest request, PaymentTraceContext context) {
        if (request == null || context == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Payment payment = payments.lockByPaymentId(request.paymentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "환불 대상 결제가 없습니다."));
        payment.requireRefundRequest(request);
        RefundExecution existing = executions.findByAttemptId(request.attemptId()).orElse(null);
        if (existing != null) {
            existing.requireSameRequest(request);
            if (inbox.firstDelivery(request.eventId(), CONSUMER_GROUP, EventType.REFUND_EXECUTION_REQUESTED)) {
                throw new BusinessException(ErrorCode.CONFLICT, "환불 실행 접수에 해당하는 Inbox가 없습니다.");
            }
            return existing;
        }
        if (executions.findByRequestEventId(request.eventId()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "실행 요청 이벤트 ID가 이미 사용되었습니다.");
        }
        RefundExecution firstPayment = executions.findFirstByPaymentIdOrderByIdAsc(request.paymentId()).orElse(null);
        RefundExecution firstRefund = executions.findFirstByRefundIdOrderByIdAsc(request.refundId()).orElse(null);
        if (firstPayment == null) {
            if (request.previousAttemptId() != null || firstRefund != null)
                throw new BusinessException(ErrorCode.CONFLICT, "원래 실패 시도 없이 새 실행을 접수할 수 없습니다.");
        } else {
            if (!firstPayment.getRefundId().equals(request.refundId()) || firstRefund == null
                    || !firstRefund.getPaymentId().equals(request.paymentId()) || request.previousAttemptId() == null)
                throw new BusinessException(ErrorCode.CONFLICT, "같은 환불의 명시적 이전 실패 시도가 필요합니다.");
            RefundExecution previous = executions.findByAttemptId(request.previousAttemptId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "이전 실패 시도가 없습니다."));
            if (previous.getStatus() != RefundExecutionStatus.FAILED || previous.getFailureEventId() == null
                    || previous.getFailureConfirmedAt() == null || previous.getRefundConfirmedAt() != null
                    || !previous.getRefundId().equals(request.refundId()) || !previous.getPaymentId().equals(request.paymentId())
                    || !previous.getOrderNo().equals(request.orderNo()) || !previous.getBuyerSubject().equals(request.buyerSubject())
                    || previous.getAmount() != request.amount() || !previous.getCurrency().equals(request.currency())
                    || request.occurredAt().isBefore(previous.getFailureConfirmedAt())
                    || executions.findByPreviousAttemptId(previous.getAttemptId()).isPresent())
                throw new BusinessException(ErrorCode.CONFLICT, "이전 시도가 미확정이거나 이미 다음 실행이 있습니다.");
            payment.requireRefundRequest(previous.request());
        }
        if (executions.findByLicenseSuspensionEventId(request.licenseSuspensionEventId()).isPresent())
            throw new BusinessException(ErrorCode.CONFLICT, "실제 정지 완료가 이미 다른 실행에 사용됐습니다.");
        if (!inbox.firstDelivery(request.eventId(), CONSUMER_GROUP, EventType.REFUND_EXECUTION_REQUESTED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox에 해당하는 환불 실행 접수가 없습니다.");
        }
        RefundExecution received = executions.saveAndFlush(RefundExecution.receive(payment, request, clock.instant()));
        traceContexts.save(received.getAttemptId(), context, received.getReceivedAt());
        return received;
    }
}
