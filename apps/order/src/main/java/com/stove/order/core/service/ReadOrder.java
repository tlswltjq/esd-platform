package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
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
