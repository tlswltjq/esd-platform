package com.stove.common.web;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * common:web 을 의존하면 예외 핸들러/추적 ID 응답 필터가 자동 등록된다.
 * (각 서비스가 @ComponentScan 범위를 넓히지 않아도 되도록 자동 구성으로 제공)
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * 추적을 구성하지 않은 실행에서도 뜨도록 {@link Tracer} 를 선택 주입한다 —
     * 없으면 {@code NOOP} 이 들어가 헤더를 붙이지 않을 뿐, 요청 처리는 그대로다.
     */
    @Bean
    public TraceIdResponseFilter traceIdResponseFilter(ObjectProvider<Tracer> tracer) {
        return new TraceIdResponseFilter(tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    /**
     * 서비스 이름은 명세 제목에만 쓰이므로 없으면 빈 문자열로 둔다 —
     * 이름을 못 읽었다고 문서 생성이 실패할 이유는 없다.
     */
    @Bean
    public ApiDocumentationCustomizer apiDocumentationCustomizer(
            @Value("${spring.application.name:}") String applicationName) {
        return new ApiDocumentationCustomizer(applicationName);
    }
}
