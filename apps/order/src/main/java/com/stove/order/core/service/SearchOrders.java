package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchOrders {
    private final OrderRepository orders;
    public Page<Order> execute(String buyer, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return orders.findByBuyerSubject(buyer, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }
}
