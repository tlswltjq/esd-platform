package com.stove.common.messaging.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * 소비 실패를 보관하고 그 키를 멈추는 자리(결정 155·160·206).
 *
 * <p>키 정지는 (소비 그룹, 토픽, 순서 키) 단위다. 정지된 키의 뒤 레코드는 처리하지 않고 같은 보관함 뒤에 쌓는다.
 * 그래야 운영자가 키 단위로 원래 순서대로 다시 넣을 수 있고, 그 사이 같은 키의 순서가 깨지지 않는다.
 *
 * <p>구현은 소비 모듈의 DB 에 있다({@code common:messaging} 의 {@code JpaKeyQuarantine}). 이 모듈은 JPA 를 모르므로
 * (DB 없는 Store·게이트웨이·웹 BFF 도 쓴다) 이 인터페이스로 저장소 의존을 끊는다 — 브로커를 바꾸려는 추상이 아니다(결정 227).
 * 이 빈이 있으면 리스너 팩토리가 재시도 소진·재시도 제외 예외를 DLT 대신 여기에 보관하고, 정지된 키를 처리 전에 거른다.
 */
public interface KeyQuarantine {

    /** 실패 사유: 앞 레코드가 격리돼 키가 멈춰 있어 처리하지 않고 쌓았다. */
    String KEY_STOPPED = "KEY_STOPPED";

    /** 그 키가 멈춰 있는가. 처리 전에 부른다. */
    boolean stopped(String consumerGroup, String topic, String key);

    /**
     * 레코드를 보관하고 키를 멈춘다. 보관과 정지는 한 기록이다. 기록이 실패하면 예외를 던진다
     * — 오류 처리기가 그 레코드의 오프셋을 넘기지 않고 다시 받는다.
     *
     * @param failure 처리 실패 원인. {@code null} 이면 키가 멈춰 있어 쌓은 것({@link #KEY_STOPPED}).
     */
    void quarantine(String consumerGroup, ConsumerRecord<String, String> record, Throwable failure);
}
