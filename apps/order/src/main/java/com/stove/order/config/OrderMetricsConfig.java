package com.stove.order.config;

import com.stove.order.core.domain.OrderMetrics;
import com.stove.order.core.domain.OrderProperties;
import com.stove.order.core.domain.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 레지스트리가 없어도 뜬다.
 *
 * <p><b>지표 수집 여부가 주문 처리 가능 여부를 좌우해서는 안 된다.</b> 액추에이터를 끈 구성이나
 * 슬라이스 테스트에서 주문 경로가 빈을 못 찾아 죽으면, 관측을 위해 넣은 것이 가용성을 깎는 셈이 된다.
 * payment 의 {@code PaymentMetricsConfig} 와 같은 판단이다.
 */
@Configuration
public class OrderMetricsConfig {

    @Bean
    public OrderMetrics orderMetrics(ObjectProvider<MeterRegistry> meterRegistry,
                                     OrderRepository orderRepository,
                                     OrderProperties orderProperties) {
        return new OrderMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new),
                orderRepository, orderProperties);
    }
}
