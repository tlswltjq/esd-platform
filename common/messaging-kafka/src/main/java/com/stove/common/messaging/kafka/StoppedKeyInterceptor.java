package com.stove.common.messaging.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * 멈춘 키의 레코드를 리스너에 넘기지 않고 보관함 뒤에 쌓는다(결정 155: 앞이 빠진 키는 뒤도 멈춘다).
 *
 * <p>흐름: 컨테이너가 리스너를 부르기 직전 {@link #intercept} → 그 (그룹, 토픽, 키)가 멈춰 있으면
 * {@link KeyQuarantine#quarantine}(사유 {@code KEY_STOPPED}) 후 {@code null} 을 돌려 리스너를 건너뛴다 →
 * 컨테이너가 그 오프셋을 처리 완료로 넘긴다. 멈춰 있지 않으면 레코드를 그대로 돌려준다.
 *
 * <p>보관(또는 정지 확인)이 실패하면 예외가 그대로 나간다. Kafka 트랜잭션이 없을 때 컨테이너는 인터셉터를 리스너 호출과 같은
 * 오류 처리 구간 안에서 부르므로, 그 예외는 리스너 실패처럼 오류 처리기로 가 제자리 재시도되고 오프셋이 넘어가지 않는다 —
 * 같은 키의 뒤 레코드가 앞지르지 못한다. Kafka 트랜잭션을 켜면 인터셉터가 그 구간 밖(트랜잭션 전)에서 돌아 이 보장이 깨지므로,
 * 자동 구성은 Kafka 트랜잭션 매니저가 있으면 기동을 멈춘다({@code KafkaConsumerAutoConfiguration}).
 *
 * <p>Boot 기본 리스너 팩토리가 이 빈을 주입받는 타입이 {@code RecordInterceptor<Object, Object>} 라 제네릭을 그렇게 둔다.
 * 리스너 컨테이너는 문자열 레코드만 받는다(Boot 의 StringDeserializer 설정).
 */
public class StoppedKeyInterceptor implements RecordInterceptor<Object, Object> {

    private final KeyQuarantine quarantine;

    public StoppedKeyInterceptor(KeyQuarantine quarantine) {
        this.quarantine = quarantine;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        String group = ConsumerThreadGroup.current();
        String key = record.key() == null ? null : record.key().toString();
        if (!quarantine.stopped(group, record.topic(), key)) {
            return record;
        }
        // 문자열 레코드만 받는다(위 설명). 인터셉터 시그니처가 Object 라 좁힌다.
        quarantine.quarantine(group, (ConsumerRecord<String, String>) (ConsumerRecord<?, ?>) record, null);
        return null;
    }
}
