package com.stove.order.application;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.order.api.dto.OrderResponse;
import com.stove.order.domain.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderResponse getOrder(String orderNo, Long memberId) {
        return orderRepository.findByOrderNo(orderNo)
                .map(order -> {
                    order.requireOwner(memberId);
                    return OrderResponse.from(order);
                })
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    public List<OrderResponse> getMyOrders(Long memberId) {
        return orderRepository.findByMemberIdOrderByIdDesc(memberId).stream()
                .map(OrderResponse::from)
                .toList();
    }
}
