package com.stove.common.messaging.kafka;

import org.springframework.kafka.support.KafkaUtils;

/**
 * 지금 소비자 스레드가 속한 소비 그룹. 격리(보관·키 정지)는 그룹 단위라 이 값이 필요하다.
 *
 * <p>팩토리를 그룹마다 만들지 않고 이것을 읽는 이유: 리스너는 {@code containerFactory} 이름으로 팩토리를 고정해 고르는데,
 * 그룹마다 만들면 (그룹 수 × 재시도 선택) 개의 빈이 생기고 리스너 선언과 팩토리 이름이 어긋날 자리가 늘어난다.
 * Spring Kafka 컨테이너는 소비자 스레드를 시작할 때 그 스레드에 그룹을 걸어 두고({@link KafkaUtils#getConsumerGroupId()}),
 * 레코드 인터셉터·오류 처리기·recoverer 는 모두 그 소비자 스레드에서 돈다.
 */
final class ConsumerThreadGroup {

    private ConsumerThreadGroup() {
    }

    /** 소비자 스레드가 아니라(그룹이 없어) 격리할 수 없으면 예외 — 그룹 없는 기록을 남기지 않는다. */
    static String current() {
        String group = KafkaUtils.getConsumerGroupId();
        if (group == null || group.isBlank()) {
            throw new IllegalStateException("소비자 스레드의 소비 그룹을 알 수 없어 격리할 수 없다");
        }
        return group;
    }
}
