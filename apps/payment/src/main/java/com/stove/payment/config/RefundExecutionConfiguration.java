package com.stove.payment.config;

import com.stove.payment.core.service.RequestFirstRefundCancellation;
import com.stove.payment.core.service.RequestRefundCancellation;
import com.stove.payment.core.service.ResolveRefundLookup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "stove.payment.refund-execution.enabled", havingValue = "true")
public class RefundExecutionConfiguration {
    @Bean RequestFirstRefundCancellation requestFirstRefundCancellation(RequestRefundCancellation cancellation,
            ResolveRefundLookup lookup) {
        // Fail startup without the durable GET worker: an unknown supplier response must have a continuation.
        return new RequestFirstRefundCancellation(cancellation);
    }
}
