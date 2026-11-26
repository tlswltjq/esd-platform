package com.stove.common.messaging.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.transaction.KafkaAwareTransactionManager;

/**
 * 컨슈머가 실패했을 때 <b>얼마나 버티고, 포기한 뒤 무엇을 하는가.</b>
 * 이 모듈을 의존하는 것만으로 기본값이 깔린다: 제자리 재시도(1·2·4초) 뒤 격리, 격리 있는 앱은 멈춘 키 거르기.
 *
 * <p>격리처는 앱의 저장소가 정한다. 소비 모듈 DB 의 {@link KeyQuarantine}(common:messaging) 빈이 있으면 거기 보관하고 그 키를
 * 멈추며(결정 155·160·206), 없으면(DB 없는 Store·게이트웨이·웹 BFF) {@code <원본토픽>.DLT} 로 보낸다.
 * {@code KeyQuarantine} 빈을 보고 판단하므로 메시징 코어의 자동 구성 뒤에 돈다.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class,
        afterName = "com.stove.common.messaging.MessagingAutoConfiguration")
@ConditionalOnBean(KafkaTemplate.class)
public class KafkaConsumerAutoConfiguration {

    /**
     * Boot 기본 리스너 팩토리가 받는 공용 오류 처리기. 재시도를 다 쓴 레코드를 앱의 격리처로 보낸다.
     * 도메인 처리가 필요한 서비스는 자기 {@link CommonErrorHandler} 빈으로 대신한다.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler stoveKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                      DeadLetterMetrics metrics,
                                                      ConsumerRetryMetrics retries,
                                                      ObjectProvider<KeyQuarantine> quarantine) {
        return DeadLetterErrorHandlers.create(
                ListenerContainerFactories.recoverer(kafkaTemplate, metrics, quarantine.getIfAvailable()),
                ConsumerRetryPolicy.backOff(), retries);
    }

    /**
     * 재시도 선택이 다른 리스너의 팩토리를 앱 {@code config} 가 만들 때 쓴다. Boot 설정은 팩토리를 만들 때 꺼낸다 —
     * Boot 의 리스너 설정이 공용 오류 처리기를 주입받으므로 여기서 미리 꺼내면 순환이 생긴다.
     */
    @Bean
    @ConditionalOnMissingBean
    public ListenerContainerFactories stoveListenerContainerFactories(
            ObjectProvider<ConcurrentKafkaListenerContainerFactoryConfigurer> configurer,
            ObjectProvider<ConsumerFactory<Object, Object>> consumers,
            KafkaTemplate<String, String> kafkaTemplate,
            DeadLetterMetrics metrics,
            ConsumerRetryMetrics retries,
            ObjectProvider<KeyQuarantine> quarantine,
            ObjectProvider<StoppedKeyInterceptor> stoppedKeys) {
        return new ListenerContainerFactories(configurer::getIfAvailable, consumers::getIfAvailable,
                ListenerContainerFactories.recoverer(kafkaTemplate, metrics, quarantine.getIfAvailable()),
                stoppedKeys.getIfAvailable(), retries);
    }

    /** DLT 유입 계측. <b>지표 수집 여부가 실패 처리 가능 여부를 좌우해서는 안 된다.</b> */
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterMetrics deadLetterMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new DeadLetterMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** 소비 재시도·격리 계측(결정 229). 모든 팩토리의 오류 처리기가 같은 청취자를 받는다. */
    @Bean
    @ConditionalOnMissingBean
    public ConsumerRetryMetrics consumerRetryMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new ConsumerRetryMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** 격리 있는 앱(소비 모듈 DB)만: 멈춘 키의 레코드를 처리하지 않고 보관함 뒤에 쌓는다. Boot 기본 팩토리가 이 빈을 받는다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(KeyQuarantine.class)
    static class StoppedKeys {

        /**
         * Kafka 트랜잭션을 켜면 컨테이너가 인터셉터를 오류 처리 구간 밖(트랜잭션 전)에서 불러, 보관 실패가 재시도되지 않고
         * 같은 키의 뒤 레코드가 앞지를 수 있다. 그래서 그 구성이면 기동을 멈춘다({@link StoppedKeyInterceptor}).
         */
        @Bean
        @ConditionalOnMissingBean
        StoppedKeyInterceptor stoveStoppedKeyInterceptor(KeyQuarantine quarantine,
                                                         ObjectProvider<KafkaAwareTransactionManager<?, ?>> kafkaTransactions) {
            if (kafkaTransactions.getIfAvailable() != null) {
                throw new IllegalStateException("Kafka 트랜잭션과 멈춘 키 인터셉터는 함께 쓸 수 없다(인터셉터가 오류 처리 밖에서 돈다)");
            }
            return new StoppedKeyInterceptor(quarantine);
        }
    }
}
