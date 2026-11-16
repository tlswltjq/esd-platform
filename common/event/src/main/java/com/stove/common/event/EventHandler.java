package com.stove.common.event;

/**
 * 메시지 한 건을 처리하는 리스너. {@link EventSubscriber} 와 함께 단다.
 *
 * <p>예외를 던지면 adapter 가 제자리 재시도(1·2·4초)하고, 다 실패하면 격리한다. 정상 반환은 처리 완료다.
 * 같은 메시지가 다시 올 수 있으므로(최소 1회 전달) 처리는 Inbox({@code ProcessedEventGuard})로 멱등이어야 한다.
 */
public interface EventHandler {

    void receive(InboundMessage message);
}
