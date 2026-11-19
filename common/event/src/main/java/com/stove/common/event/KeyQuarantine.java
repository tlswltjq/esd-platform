package com.stove.common.event;

/**
 * 소비 실패를 보관하고 그 키를 멈추는 자리(결정 155·160·206). 브로커 adapter 는 이 둘만 부른다.
 *
 * <p>키 정지는 (소비 그룹, 토픽, 순서 키) 단위다. 정지된 키의 뒤 메시지는 처리하지 않고 같은 보관함 뒤에 쌓는다.
 * 그래야 운영자가 키 단위로 원래 순서대로 다시 넣을 수 있고, 그 사이 같은 키의 순서가 깨지지 않는다(결정 202).
 * 구현은 소비 모듈의 DB 에 있다(브로커가 바뀌어도 그대로다).
 */
public interface KeyQuarantine {

    /** 실패 사유: 앞 메시지가 격리돼 키가 멈춰 있어 처리하지 않고 쌓았다. */
    String KEY_STOPPED = "KEY_STOPPED";

    /** 그 키가 멈춰 있는가. 처리 전에 부른다. */
    boolean stopped(String consumerGroup, String topic, String key);

    /**
     * 메시지를 보관하고 키를 멈춘다. 보관과 정지는 한 기록이다. 기록이 실패하면 예외를 던진다
     * — adapter 는 그 메시지를 처리 완료로 넘기지 말고 다시 받아야 한다.
     *
     * @param failure 처리 실패 원인. {@code null} 이면 키가 멈춰 있어 쌓은 것({@link #KEY_STOPPED}).
     */
    void quarantine(String consumerGroup, InboundMessage message, Throwable failure);
}
