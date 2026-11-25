package com.stove.common.messaging.delivery;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;

/** {@code @EventSubscriber} 하나가 구독하는 (소비 그룹, 토픽) 한 쌍. Kafka 가 아닌 adapter 가 이 목록으로 수신을 연다. */
public record SubscribedHandler(String group, String topic, EventHandler handler,
                                Class<? extends RuntimeException>[] noRetryFor) {

    public static List<SubscribedHandler> discover(ApplicationContext beans) {
        List<SubscribedHandler> found = new ArrayList<>();
        for (Map.Entry<String, Object> entry : beans.getBeansWithAnnotation(EventSubscriber.class).entrySet()) {
            if (!(entry.getValue() instanceof EventHandler handler)) {
                throw new IllegalStateException("@EventSubscriber 는 EventHandler 구현에만 단다: " + entry.getKey());
            }
            EventSubscriber subscription = beans.findAnnotationOnBean(entry.getKey(), EventSubscriber.class);
            if (subscription.retryUntilSuccess()) {
                // DB 격리 adapter는 재시도 상한 뒤 보관이 전제다. 성공까지 재시도는 DB 없는 모듈의 Kafka 소비만 쓴다(결정 226).
                throw new IllegalStateException("retryUntilSuccess 는 Kafka adapter만 지원한다: " + entry.getKey());
            }
            for (String topic : subscription.topics()) {
                found.add(new SubscribedHandler(subscription.group(), topic, handler, subscription.noRetryFor()));
            }
        }
        return found;
    }
}
