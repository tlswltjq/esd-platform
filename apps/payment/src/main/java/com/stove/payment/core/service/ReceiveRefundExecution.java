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
        if (executions.findFirstByPaymentIdOrderByIdAsc(request.paymentId()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "기존 환불 실행의 결과가 미확정입니다.");
        }
        if (executions.findFirstByRefundIdOrderByIdAsc(request.refundId()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "환불 식별자가 이미 다른 실행에 연결돼 있습니다.");
        }
        if (!inbox.firstDelivery(request.eventId(), CONSUMER_GROUP, EventType.REFUND_EXECUTION_REQUESTED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox에 해당하는 환불 실행 접수가 없습니다.");
        }
        RefundExecution received = executions.saveAndFlush(RefundExecution.receive(payment, request, clock.instant()));
        traceContexts.save(received.getAttemptId(), context, received.getReceivedAt());
        return received;
    }
}
