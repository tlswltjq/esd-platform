package com.stove.common.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * DLT 발행자를 만드는 유일한 자리. <b>보내는 것과 세는 것을 함께 묶는다</b> —
 * 실제로 이름과 계측이 각각 한 번씩 갈렸다. docs/code-notes.md
 */
public final class DeadLetterPublisher {

    private DeadLetterPublisher() {
    }

    /**
     * 재시도를 소진한 레코드를 {@code <원본토픽>.DLT} 의 같은 파티션으로 보내고 그 사실을 센다.
     * 세는 것은 <b>발행이 성공한 뒤</b>여야 한다.
     */
    public static ConsumerRecordRecoverer to(KafkaTemplate<String, String> kafkaTemplate,
                                             DeadLetterMetrics metrics) {
        ConsumerRecordRecoverer publisher =
                new DeadLetterPublishingRecoverer(kafkaTemplate, DeadLetterTopics::of);
        return (record, exception) -> {
            publisher.accept(record, exception);
            metrics.recordDeadLettered(record.topic());
        };
    }
}
