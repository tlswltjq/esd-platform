package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderValues;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(isolation = Isolation.READ_COMMITTED)
public class CloseExpiredOrder {
    private final OrderRepository orders;
    private final Clock clock;
    private final RecordOrderExpiry expiry;

    public CloseExpiredOrder(OrderRepository orders, Clock clock,RecordOrderExpiry expiry) { this.orders = orders; this.clock = clock; this.expiry=expiry; }

    public boolean execute(String orderNo) {
        if (orderNo == null || !orderNo.matches(OrderValues.UUID_PATTERN))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Order order = orders.lockByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        boolean changed=order.expire(clock.instant().truncatedTo(ChronoUnit.MICROS));
        if(changed)expiry.execute(order);
        return changed;
    }
}
