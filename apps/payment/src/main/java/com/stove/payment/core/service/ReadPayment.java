package com.stove.payment.core.service;

import com.stove.common.web.error.*;
import com.stove.payment.core.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadPayment {
    private final PaymentRepository payments;

    public Payment execute(String orderNo, String buyer) {
        return payments.findByOrderNoAndBuyerSubject(orderNo, buyer)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
