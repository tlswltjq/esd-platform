package com.stove.common.messaging.ops;

import com.stove.common.kafka.contract.EventType;
import java.util.Set;

/**
 * 운영 재발행([결정 129])을 허용하는 이벤트 타입. <b>목록은 여기 한 곳에만 둔다.</b>
 *
 * <p>행은 그 이벤트를 소유한 모듈의 Outbox 에만 있으므로, 한 목록을 모든 모듈이 같이 써도
 * {@code OrderPaid} 는 Order 에서만, {@code LicenseGranted} 는 License 에서만 재발행된다.
 * 받는 쪽은 Inbox 로 같은 ID 를 거르므로 이미 처리된 이벤트의 재발행은 효과가 없다.
 * 정지·복구·승인 정정 이벤트는 다음 범위에서 이 목록에 더한다(order tasks.md).
 */
public final class OutboxRepublishTargets {

    /** 재발행할 수 있는 이벤트 타입. */
    public static final Set<String> EVENT_TYPES = Set.of(
            EventType.ORDER_PAID,
            EventType.LICENSE_GRANTED);

    private OutboxRepublishTargets() {
    }

    /** {@code eventType} 의 재발행 허용 여부. */
    public static boolean allows(String eventType) {
        return eventType != null && EVENT_TYPES.contains(eventType);
    }
}
