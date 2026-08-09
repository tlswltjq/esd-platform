package com.stove.common.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 컨슈머가 실패했을 때 <b>얼마나 버티고, 포기한 뒤 무엇을 하는가</b>를 정한다.
 *
 * <p>이 모듈을 의존하는 것만으로 두 기본값이 깔린다. 서비스마다 정하게 두면 잊는 곳이 생기고,
 * 장애 시 서비스마다 다르게 굴어 무슨 일이 벌어질지 예측할 수 없게 된다.
 */
@Slf4j
@AutoConfiguration
public class KafkaConsumerAutoConfiguration {

    /**
     * 재시도가 소진된 레코드를 DLT 로 보낸다.
     *
     * <p>예전에는 로그만 남기고 건너뛰었다. 파티션이 막히지 않는다는 점은 옳았지만
     * <b>되돌릴 대상이 없다는 것이 문제였다</b> — 오프셋은 커밋됐고 메시지는 사라진다.
     * 커넥션 풀이 8초 고갈되면 그 사이의 이벤트가 로그 한 줄만 남기고 소멸했다.
     *
     * <p>DLT 로 보내면 "관측 가능한 <b>포기</b>"가 "관측 가능한 <b>연기</b>"가 된다.
     * 파티션이 안 막힌다는 성질은 그대로 두고 유실만 없애므로 맞바꿈이 아니다.
     *
     * <p>보내는 곳은 {@code <원본토픽>.DLT} 의 같은 파티션 번호다(기본 해석기).
     * 실패 원인·원본 토픽/파티션/오프셋이 헤더로 보존되고, 계약 헤더와 {@code traceparent} 도
     * 원본 그대로 따라간다 — DLT 레코드에서 그 요청의 전체 트레이스로 바로 갈 수 있다.
     *
     * <p>도메인 처리가 필요한 서비스는 자기 {@link CommonErrorHandler} 빈으로 이 기본값을 대신한다
     * (예: {@code license} 는 지급 실패를 Saga 보상으로 종결하므로 그 경로만 DLT 로 보내지 않는다).
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler stoveKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                      DeadLetterMetrics metrics) {
        ConsumerRecordRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(
                (record, exception) -> {
                    log.error("재시도 소진 — DLT 로 보낸다 topic={} partition={} offset={} key={}",
                            record.topic(), record.partition(), record.offset(), record.key(), exception);
                    toDeadLetterTopic.accept(record, exception);
                    metrics.recordDeadLettered(record.topic());
                },
                ConsumerRetryPolicy.backOff());
    }

    /**
     * DLT 유입 계측. 액추에이터가 없는 구성에서도 뜨도록 레지스트리가 없으면 로컬 것을 만든다 —
     * 지표 수집 여부가 실패 처리 가능 여부를 좌우해서는 안 된다({@code OutboxMetrics} 와 같은 판단).
     */
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterMetrics deadLetterMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new DeadLetterMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }
}
