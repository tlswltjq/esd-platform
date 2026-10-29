package com.stove.order.core.service;

import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;

@Slf4j
public class CloseExpiredOrders {
    private final OrderRepository orders;
    private final CloseExpiredOrder close;
    private final Clock clock;
    private final int batchSize;
    public CloseExpiredOrders(OrderRepository orders,CloseExpiredOrder close,Clock clock,int batchSize) {
        if(batchSize<1)throw new IllegalArgumentException("A bounded expiry batch is required");
        this.orders=orders; this.close=close; this.clock=clock; this.batchSize=batchSize;
    }
    public void execute() {
        Instant cutoff=clock.instant().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofMinutes(30));
        List<String> due=orders.expiryCandidates(OrderStatus.PENDING,cutoff,PageRequest.of(0,batchSize));
        for(String orderNo:due) {
            try { close.execute(orderNo); }
            catch(RuntimeException unavailable) { log.warn("Order expiry remains pending: orderNo={}, errorType={}",orderNo,unavailable.getClass().getSimpleName()); }
        }
    }
}
