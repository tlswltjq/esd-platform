package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentCheckout;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.net.URI;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class ReadPaymentCheckout {
    private final ReadPayment readPayment;
    private final String storeId;
    private final String channelKey;
    private final URI webhookUrl;
    private final PaymentApprovalRepository approvals;
    private final Clock clock;

    public ReadPaymentCheckout(ReadPayment readPayment, String storeId, String channelKey, URI webhookUrl,
            PaymentApprovalRepository approvals,Clock clock) {
        this.readPayment = readPayment;
        this.storeId = storeId;
        this.channelKey = channelKey;
        this.webhookUrl = webhookUrl; this.approvals=approvals; this.clock=clock;
    }

    public PaymentCheckout execute(String orderNo, String buyer) {
        Payment payment = readPayment.execute(orderNo, buyer);
        if (!"PORTONE_TEST".equals(payment.getProvider()) || payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제창을 열 수 있는 포트원 테스트 결제가 아닙니다.");
        }
        if (approvals.findFirstByPaymentIdAndStatusInOrderByIdDesc(payment.getPaymentId(),List.of(PaymentApprovalStatus.REQUESTED,
                PaymentApprovalStatus.ADMITTED,PaymentApprovalStatus.REVIEW_REQUIRED,PaymentApprovalStatus.FAILURE_CONFIRMED)).isPresent())
            throw new BusinessException(PaymentErrorCode.APPROVAL_IN_PROGRESS);
        if (!clock.instant().isBefore(payment.getOrderOccurredAt().plus(Duration.ofMinutes(30))))
            throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED);
        return new PaymentCheckout(payment.getPaymentId(), payment.getOrderNo(), payment.getTitle(),
                payment.getAmount(), payment.getCurrency(), storeId, channelKey, webhookUrl);
    }
}
