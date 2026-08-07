package com.stove.archfixture.consumergroup.missing;

import com.stove.common.messaging.inbox.ProcessedEventGuard;

/**
 * 위반 — 가드는 쓰는데 멱등 키가 {@code CONSUMER_GROUP} 상수 밖으로 나갔다.
 *
 * <p><b>이 픽스처가 규칙의 공허 통과를 증명한다.</b> 제외 조건을 상수의 부재로 판정하면
 * 이 앱은 "가드를 안 쓰는 앱" 과 구별되지 않아 조용히 통과한다 — 검사할 것이 없는 게 아니라
 * 검사 대상이 사라진 것인데도. 그래서 판정을 {@link ProcessedEventGuard} 주입 여부로 옮겼다.
 */
public class MissingConstantService {

    private final ProcessedEventGuard processedEventGuard;

    public MissingConstantService(ProcessedEventGuard processedEventGuard) {
        this.processedEventGuard = processedEventGuard;
    }

    public boolean handle(String eventId, String eventType) {
        // 상수를 지우고 리터럴로 되돌린 상태. 규칙이 대조할 두 번째 출처를 잃었다.
        return processedEventGuard.firstDelivery(eventId, "missing", eventType);
    }
}
