package com.stove.payment.config;

import com.stove.payment.core.domain.PaymentMetrics;
import com.stove.payment.core.domain.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결제 지표 빈.
 *
 * <p>레지스트리가 없으면 로컬 것을 만든다 — {@code OutboxMetrics}·{@code DeadLetterMetrics} 와 같은 판단이다.
 * <b>지표 수집 여부가 결제 처리 가능 여부를 좌우해서는 안 된다.</b> 액추에이터를 끈 구성이나
 * 슬라이스 테스트에서 결제 경로가 빈을 못 찾아 죽으면, 관측을 위해 넣은 것이 가용성을 깎는 셈이 된다.
 */
@Configuration
public class PaymentMetricsConfig {

    @Bean
    public PaymentMetrics paymentMetrics(ObjectProvider<MeterRegistry> meterRegistry,
                                        PaymentRepository paymentRepository) {
        return new PaymentMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new), paymentRepository);
    }
}
