package com.stove.common.kafka.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * DLT 운영 API 를 자동 등록한다.
 *
 * <p>DLT 로 보내는 것과 되돌리는 것을 같은 모듈에 두었다 — 한쪽만 있는 상태가
 * 이 작업을 하게 만든 문제였기 때문이다(보낼 수는 있는데 되돌릴 수 없었다).
 *
 * <p>노출을 좁히고 싶은 배포에서는 {@code stove.messaging.ops.enabled=false} 로 끈다.
 * Outbox 운영 API 와 같은 스위치를 쓴다 — 운영자 입장에서 둘은 한 도구다.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "stove.messaging.ops", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class DltOpsAutoConfiguration {

    /**
     * 재투입 전용 컨슈머 그룹을 쓴다.
     *
     * <p>서비스의 처리용 그룹을 그대로 쓰면 <b>DLT 를 읽은 오프셋이 정상 처리 오프셋과 섞인다.</b>
     * 이름을 서비스명에서 파생시키므로 따로 관리할 것이 없다
     * (decisions.md 16번의 "그룹 이름은 한 곳에서만 정한다" 와 어긋나지 않는다 —
     * 그 규칙이 다루는 것은 도메인 이벤트 처리 그룹이고, 이것은 Inbox 멱등 키로 쓰이지 않는다).
     */
    @Bean
    @ConditionalOnMissingBean
    public DltOpsService dltOpsService(ConsumerFactory<String, String> consumerFactory,
                                       KafkaTemplate<String, String> kafkaTemplate,
                                       @Value("${spring.application.name:stove}") String applicationName) {
        return new DltOpsService(consumerFactory, kafkaTemplate, applicationName + "-dlt-ops");
    }

    @Bean
    @ConditionalOnMissingBean
    public DltOpsController dltOpsController(DltOpsService dltOpsService) {
        return new DltOpsController(dltOpsService);
    }
}
