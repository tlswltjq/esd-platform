package com.stove.order.config;

import com.stove.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 아웃바운드 HTTP 호출의 <b>횡단 관심사</b>만 전역으로 건다.
 *
 * <p>대상별 설정(baseUrl, 타임아웃)은 각 어댑터가 소유한다. 이름 붙은 {@code RestClient} 빈을
 * 두지 않으므로 어댑터 클래스명과 빈 이름이 충돌할 여지가 없다 — 자동 설정된
 * {@code RestClient.Builder} 는 프로토타입이라 주입 지점마다 이 커스터마이저가 적용된
 * 새 빌더가 전달된다.
 */
@Configuration
public class RestClientConfig {

    /** 상관관계 ID를 헤더로 전파해 서비스 경계를 넘는 로그 추적을 가능하게 한다. */
    @Bean
    public RestClientCustomizer correlationIdCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                request.getHeaders().add(CorrelationIdFilter.HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
    }
}
