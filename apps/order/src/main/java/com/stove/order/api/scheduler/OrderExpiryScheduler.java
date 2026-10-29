package com.stove.order.api.scheduler;

import com.stove.order.core.service.CloseExpiredOrders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="stove.order.expiry-enabled",havingValue="true",matchIfMissing=true)
public class OrderExpiryScheduler {
    private final CloseExpiredOrders resume;
    public OrderExpiryScheduler(CloseExpiredOrders resume) { this.resume=resume; }
    @Scheduled(fixedDelay=1000,scheduler="orderExpiryTaskScheduler")
    public void run() { resume.execute(); }
}
