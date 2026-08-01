package com.stove.order.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public Order getOrder(String orderNo, Long memberId) {
        return orderRepository.findByOrderNo(orderNo)
                .map(order -> {
                    order.requireOwner(memberId);
                    return order;
                })
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    public List<Order> getMyOrders(Long memberId) {
        return orderRepository.findByMemberIdOrderByIdDesc(memberId);
    }
}
