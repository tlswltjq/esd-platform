package com.stove.common.messaging.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

/** DB 격리 adapter(RabbitMQ·Redis Streams·직접 전달)가 쓰는 구독 목록. */
class SubscribedHandlerTest {

    @Test
    void discoversEachTopicOfASubscriber() {
        try (GenericApplicationContext beans = context(PlainHandler.class)) {
            List<SubscribedHandler> found = SubscribedHandler.discover(beans);
            assertThat(found).extracting(SubscribedHandler::topic).containsExactly("fixture.a.v1");
        }
    }

    @Test
    void refusesRetryUntilSuccessBecauseTheseAdaptersQuarantineAfterTheRetryLimit() {
        // 결정 226: 성공까지 재시도는 DB 없는 모듈의 Kafka 소비만 쓴다. 다른 adapter가 조용히 무시하면 4회 뒤 격리된다.
        try (GenericApplicationContext beans = context(PersistentHandler.class)) {
            assertThatThrownBy(() -> SubscribedHandler.discover(beans))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("retryUntilSuccess");
        }
    }

    private static GenericApplicationContext context(Class<? extends EventHandler> handler) {
        GenericApplicationContext beans = new GenericApplicationContext();
        beans.registerBean("handler", handler);
        beans.refresh();
        return beans;
    }

    @EventSubscriber(topics = "fixture.a.v1", group = "fixture-plain")
    static class PlainHandler implements EventHandler {
        @Override
        public void receive(InboundMessage message) {
        }
    }

    @EventSubscriber(topics = "fixture.a.v1", group = "fixture-persistent", retryUntilSuccess = true)
    static class PersistentHandler implements EventHandler {
        @Override
        public void receive(InboundMessage message) {
        }
    }
}
