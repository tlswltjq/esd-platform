package com.stove.order.core.service;

import com.stove.common.web.error.*;
import com.stove.order.core.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadOrder {
    private final OrderRepository orders;
    public Order execute(String orderNo, String buyer) {
        return orders.findByOrderNoAndBuyerSubject(orderNo, buyer)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
