package com.stove.common.event;

import java.util.concurrent.CompletableFuture;

/**
 * 메시지를 브로커로 보내는 자리(발행 포트). Outbox 릴레이만 쓴다 — 업무 코드는 Outbox 에 적을 뿐 직접 보내지 않는다.
 *
 * <p>돌려준 future 가 정상 완료되면 브로커가 그 메시지를 받았다(내구성 있게 저장했다)는 뜻이다.
 * 릴레이는 완료를 기다린 뒤에만 같은 키의 다음 메시지를 보낸다(결정 202). 실패하면 예외로 완료한다.
 */
public interface MessagePublisher {

    CompletableFuture<Void> publish(OutboundMessage message);
}
