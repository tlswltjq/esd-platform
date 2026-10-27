package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.port.PortOneWebhookReceipts;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReceivePortOneWebhook {
    private final PaymentRepository payments;
    private final PortOneWebhookReceipts receipts;
    private final Clock clock;
    private final RefundExecutionRepository executions;

    public void execute(PortOneWebhookNotice notice) {
        if (notice == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Payment payment = payments.lockByPaymentId(notice.paymentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "알림에 해당하는 결제가 없습니다."));
        if (!"PORTONE_TEST".equals(payment.getProvider())) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제 공급자가 다릅니다.");
        }
        String existing = receipts.findPayloadHash(notice.messageId()).orElse(null);
        if (existing != null) {
            if (!existing.equals(notice.payloadHash())) throw new BusinessException(ErrorCode.CONFLICT, "웹훅 ID의 본문이 다릅니다.");
            return;
        }
        Instant receivedAt = clock.instant();
        receipts.save(notice, receivedAt);
        payment.requestRecheck(receivedAt);
        if (notice.type().equals("Transaction.CancelPending") || notice.type().equals("Transaction.PartialCancelled")
                || notice.type().equals("Transaction.Cancelled")) {
            executions.findFirstByPaymentIdOrderByIdAsc(payment.getPaymentId()).ifPresent(received ->
                    executions.lockByAttemptId(received.getAttemptId()).orElseThrow().getLookupSchedule().wake(receivedAt));
        }
    }
}
