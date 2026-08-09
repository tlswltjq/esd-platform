package com.stove.common.messaging.ops;

import com.stove.common.messaging.outbox.OutboxEventRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Outbox 운영 API 를 자동 등록한다.
 *
 * <p>본체({@code MessagingAutoConfiguration})에서 떼어 둔 이유는 <b>켜고 끌 수 있어야 하기 때문</b>이다.
 * Outbox/Inbox 는 서비스가 도는 데 필요하지만 운영 API 는 그렇지 않다 —
 * 노출 범위를 좁히고 싶은 배포에서는 {@code stove.messaging.ops.enabled=false} 로 끈다.
 * DLT 운영 API 도 같은 스위치를 쓴다({@code common:kafka}) — 운영자에게 둘은 한 도구다.
 *
 * <p>웹 애플리케이션일 때만 뜬다. {@code common:messaging} 은 spring-web 을 {@code compileOnly}
 * 로만 걸어 두므로, 웹이 아닌 실행에서 이 클래스가 없어도 Outbox 는 그대로 돈다.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "stove.messaging.ops", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class MessagingOpsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxOpsService outboxOpsService(OutboxEventRepository repository) {
        return new OutboxOpsService(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxOpsController outboxOpsController(OutboxOpsService outboxOpsService) {
        return new OutboxOpsController(outboxOpsService);
    }
}
