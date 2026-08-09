package com.stove.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

/**
 * DLT 이름 규칙의 단일 출처.
 *
 * <p>보내는 쪽({@code KafkaConsumerAutoConfiguration})과 되돌리는 쪽({@code DltOpsService})이
 * 같은 규칙을 알아야 한다. 한쪽만 바뀌면 재투입이 <b>"그런 토픽이 없다"</b>로 조용히 실패한다.
 */
public final class DeadLetterTopics {

    /**
     * 접미사.
     *
     * <p>스프링 기본값 {@code -dlt} 를 쓰지 않는다. 이 저장소의 토픽은 점으로 끊는 규칙인데
     * ({@code stove.<애그리거트>.v1}) 거기에 하이픈이 붙으면 {@code stove.payment.v1-dlt} 가 되어
     * 구분자가 섞인다. 이름이 규칙에서 벗어나면 문서·알람·운영 명령이 예외를 하나씩 안게 된다.
     */
    public static final String SUFFIX = ".DLT";

    private DeadLetterTopics() {
    }

    /**
     * 실패한 레코드가 갈 자리. <b>파티션 번호를 유지한다</b> —
     * 같은 애그리거트의 순서가 DLT 안에서도 보존되어, 재투입할 때 원래 순서대로 나간다.
     */
    public static TopicPartition of(ConsumerRecord<?, ?> record, Exception cause) {
        return new TopicPartition(nameFor(record.topic()), record.partition());
    }

    public static String nameFor(String originalTopic) {
        return originalTopic + SUFFIX;
    }

    /** DLT 이름에서 원본을 되돌린다. 헤더가 없는 레코드를 위한 최후 수단이다. */
    public static String originalOf(String deadLetterTopic) {
        if (!deadLetterTopic.endsWith(SUFFIX)) {
            throw new IllegalStateException("DLT 이름 규칙에 맞지 않는다: " + deadLetterTopic);
        }
        return deadLetterTopic.substring(0, deadLetterTopic.length() - SUFFIX.length());
    }
}
