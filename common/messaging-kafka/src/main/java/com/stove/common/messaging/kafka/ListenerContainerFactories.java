package com.stove.common.messaging.kafka;

import java.util.Properties;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.BackOff;

/**
 * 재시도 선택이 다른 리스너가 쓸 컨테이너 팩토리를 만든다. 앱의 {@code config} 가 이것으로 팩토리 빈을 만들고,
 * 리스너는 {@code @KafkaListener(containerFactory = ...)} 로 그 빈 이름을 고른다(결정 227).
 *
 * <p>선택이 없는 리스너는 Boot 기본 팩토리({@code kafkaListenerContainerFactory})를 쓴다. 그 팩토리는 이 모듈의 자동 구성이 등록한
 * 공용 오류 처리기({@code stoveKafkaErrorHandler})와 멈춘 키 인터셉터({@link StoppedKeyInterceptor}, 격리 있는 앱만)를 받는다.
 * 여기서 만드는 팩토리도 같은 Boot 설정(직렬화·동시성 = 파티션 수·관측)에서 시작해 오류 처리·가져오는 건수만 바꾼다.
 *
 * <ul>
 *   <li>{@link #noRetryFor} — 재시도해도 결과가 같은 예외는 첫 실패에 격리한다.</li>
 *   <li>{@link #oneAtATime} — 한 번에 한 건만 가져온다({@code max.poll.records=1}). 한 건 처리에 외부 HTTP 대기처럼 오래 걸리는
 *       리스너가 쓴다 — 여러 건을 한꺼번에 가져오면 뒤 건들이 {@code max.poll.interval.ms} 를 넘긴다.</li>
 *   <li>{@link #retryUntilSuccess} — 일시 실패를 격리하지 않고 성공할 때까지 제자리 재시도한다(그동안 그 파티션이 멈춘다).
 *       {@code noRetryFor} 예외(영구 실패)만 격리로 간다. 멱등이고 순서에 무관하며 빠지면 안 되는 사실을 DB 없는 모듈이 받을 때만
 *       쓴다(결정 226: 웹 BFF·API 게이트웨이의 계정 접근 변경). 소비 지연 게이지로 멈춤을 감지해야 한다.</li>
 * </ul>
 *
 * <p>격리처는 앱마다 하나다: 소비 모듈 DB 가 있으면({@link KeyQuarantine}) 거기 보관하고 키를 멈추고, 없으면 DLT 로 보낸다.
 * 반환한 팩토리를 빈으로 등록해야 한다 — 컨테이너가 시작할 때 컨텍스트에서 {@code ObservationRegistry} 를 찾는다(빈이 아니면
 * 애플리케이션 컨텍스트를 받지 못해 그 리스너만 소비 관측이 꺼진다).
 */
public class ListenerContainerFactories {

    private final Supplier<ConcurrentKafkaListenerContainerFactoryConfigurer> boot;
    private final Supplier<ConsumerFactory<Object, Object>> consumers;
    private final ConsumerRecordRecoverer recoverer;
    private final RecordInterceptor<Object, Object> stoppedKeys;
    private final RetryListener retries;

    /**
     * @param boot Boot 리스너 팩토리 설정. 팩토리를 만들 때 꺼낸다. 그때 없으면(Boot Kafka 설정이 없음) 예외.
     * @param consumers Boot 소비자 팩토리. {@code boot} 와 같이 팩토리를 만들 때 꺼낸다.
     * @param recoverer 재시도를 다 쓴(또는 재시도 제외) 레코드의 격리처({@link #recoverer})
     * @param stoppedKeys 멈춘 키 인터셉터. 격리 없는 앱은 {@code null}
     */
    public ListenerContainerFactories(Supplier<ConcurrentKafkaListenerContainerFactoryConfigurer> boot,
                                      Supplier<ConsumerFactory<Object, Object>> consumers,
                                      ConsumerRecordRecoverer recoverer,
                                      RecordInterceptor<Object, Object> stoppedKeys) {
        this(boot, consumers, recoverer, stoppedKeys, null);
    }

    /** @param retries 재시도·격리를 세는 청취자({@link ConsumerRetryMetrics}). {@code null} 이면 세지 않는다. 처리 동작은 같다. */
    public ListenerContainerFactories(Supplier<ConcurrentKafkaListenerContainerFactoryConfigurer> boot,
                                      Supplier<ConsumerFactory<Object, Object>> consumers,
                                      ConsumerRecordRecoverer recoverer,
                                      RecordInterceptor<Object, Object> stoppedKeys,
                                      RetryListener retries) {
        this.boot = boot;
        this.consumers = consumers;
        this.recoverer = recoverer;
        this.stoppedKeys = stoppedKeys;
        this.retries = retries;
    }

    /**
     * 앱의 격리처. 소비 모듈 DB 가 있으면 거기 보관하고 그 키를 멈춘다(결정 155·206). 보관이 실패하면 예외가 나가
     * 오류 처리기가 오프셋을 넘기지 않는다(다시 받는다). 없으면 {@code <원본토픽>.DLT} 로 보낸다(DB 없는 앱).
     */
    @SuppressWarnings("unchecked")
    public static ConsumerRecordRecoverer recoverer(KafkaTemplate<String, String> kafkaTemplate, DeadLetterMetrics metrics,
                                                    KeyQuarantine quarantine) {
        if (quarantine == null) {
            return DeadLetterPublisher.to(kafkaTemplate, metrics);
        }
        // 리스너 컨테이너는 문자열 레코드만 받는다(Boot 의 StringDeserializer 설정). 오류 처리기 시그니처가 와일드카드라 좁힌다.
        return (record, exception) -> quarantine.quarantine(ConsumerThreadGroup.current(),
                (ConsumerRecord<String, String>) record, exception);
    }

    /** Boot 기본 팩토리와 같은 팩토리. 기본 팩토리를 그대로 쓰지 못하는 곳(통합 테스트의 격리처 바꾸기)이 쓴다. */
    public ConcurrentKafkaListenerContainerFactory<Object, Object> standard() {
        return create(ConsumerRetryPolicy.backOff(), false);
    }

    @SafeVarargs
    public final ConcurrentKafkaListenerContainerFactory<Object, Object> noRetryFor(Class<? extends Exception>... noRetryFor) {
        return create(ConsumerRetryPolicy.backOff(), false, noRetryFor);
    }

    public ConcurrentKafkaListenerContainerFactory<Object, Object> oneAtATime() {
        return create(ConsumerRetryPolicy.backOff(), true);
    }

    @SafeVarargs
    public final ConcurrentKafkaListenerContainerFactory<Object, Object> retryUntilSuccess(
            Class<? extends Exception>... noRetryFor) {
        return create(ConsumerRetryPolicy.untilSuccess(), false, noRetryFor);
    }

    @SafeVarargs
    private ConcurrentKafkaListenerContainerFactory<Object, Object> create(BackOff backOff, boolean oneAtATime,
                                                                          Class<? extends Exception>... noRetryFor) {
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer = boot.get();
        ConsumerFactory<Object, Object> consumerFactory = consumers.get();
        if (configurer == null || consumerFactory == null) {
            throw new IllegalStateException("리스너 팩토리를 만들 Boot Kafka 설정(KafkaAutoConfiguration)이 없다");
        }
        // Boot configurer 의 고정 제네릭 시그니처다. 업무 입력을 Object 로 넓혀 전달하지 않는다.
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(DeadLetterErrorHandlers.create(recoverer, backOff, retries, noRetryFor));
        factory.setRecordInterceptor(stoppedKeys);
        if (oneAtATime) {
            Properties properties = new Properties();
            properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
            // 엔드포인트 초기화가 원형의 소비자 속성을 바꾸므로 실제 컨테이너에 마지막으로 건다.
            factory.setContainerCustomizer(container ->
                    container.getContainerProperties().setKafkaConsumerProperties(properties));
        }
        return factory;
    }
}
