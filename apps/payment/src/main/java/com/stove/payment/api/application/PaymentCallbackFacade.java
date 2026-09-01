package com.stove.payment.api.application;

import com.stove.payment.core.domain.PaymentCancellation;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.port.PgClient;
import com.stove.payment.core.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 승인 콜백 오케스트레이션. 파사드가 필요한 것은 <b>결제창 만료 뒤 도착한 승인</b> 하나 때문이고,
 * 순서와 이유는 {@link RefundFacade} 와 같다. docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCallbackFacade {

    private final PaymentService paymentService;
    private final PgClient pgClient;

    public void approve(PgApproval approval) {
        PaymentCancellation cancellation = paymentService.handleApproval(approval);
        if (!cancellation.pgRefundRequired()) {
            return;
        }
        pgClient.cancel(cancellation.pgTxId(), cancellation.amount(), PaymentService.CHECKOUT_EXPIRED);
        paymentService.completeCancel(approval.orderNo(), PaymentService.CHECKOUT_EXPIRED);
        log.warn("결제창 만료 자동 환불 완료 orderNo={}", approval.orderNo());
    }
}
