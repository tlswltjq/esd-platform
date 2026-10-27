package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentCheckout;
import com.stove.payment.core.domain.PaymentStatus;
import java.net.URI;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class ReadPaymentCheckout {
    private final ReadPayment readPayment;
    private final String storeId;
    private final String channelKey;
    private final URI webhookUrl;

    public ReadPaymentCheckout(ReadPayment readPayment, String storeId, String channelKey, URI webhookUrl) {
        this.readPayment = readPayment;
        this.storeId = storeId;
        this.channelKey = channelKey;
        this.webhookUrl = webhookUrl;
    }

    public PaymentCheckout execute(String orderNo, String buyer) {
        Payment payment = readPayment.execute(orderNo, buyer);
        if (!"PORTONE_TEST".equals(payment.getProvider()) || payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제창을 열 수 있는 포트원 테스트 결제가 아닙니다.");
        }
        return new PaymentCheckout(payment.getPaymentId(), payment.getOrderNo(), payment.getTitle(),
                payment.getAmount(), payment.getCurrency(), storeId, channelKey, webhookUrl);
    }
}
