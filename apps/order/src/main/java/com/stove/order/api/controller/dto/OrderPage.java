package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.Order;
import org.springframework.data.domain.Page;
import java.util.List;

public record OrderPage(List<OrderResponse> content, int page, int size, long totalElements, int totalPages) {
    public static OrderPage from(Page<Order> page) {
        return new OrderPage(page.map(OrderResponse::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
