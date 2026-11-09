package com.stove.common.messaging.ops;

import com.stove.common.kafka.contract.EventType;
import java.util.Map;
import java.util.Set;

/**
 * 운영 재발행([결정 129])을 허용하는 이벤트 타입. <b>목록은 여기 한 곳에만 두고, 소유 모듈별로 나눈다.</b>
 *
 * <p>각 모듈은 자기가 Outbox 로 내보내는 이벤트만 재발행한다. 모듈은
 * {@code stove.messaging.ops.republish-owner} 로 자기 이름을 밝히고, 재발행 서비스는 그 모듈의 목록만 받는다.
 * 그래서 다른 모듈 타입의 행이 잘못 들어와 있어도 그 모듈에서는 재발행되지 않는다(400).
 * 받는 쪽은 Inbox 로 같은 ID 를 거르므로 이미 처리된 이벤트의 재발행은 효과가 없다.
 *
 * <ul>
 *   <li>Order: {@code OrderPaid}, 정지·복구·승인 정정 요청(결정 125·129 범위 확장)</li>
 *   <li>License: {@code LicenseGranted}, 정지·복구·승인 정정 결과</li>
 * </ul>
 */
public final class OutboxRepublishTargets {

    /** Order 모듈의 소유자 이름. */
    public static final String ORDER = "order";
    /** License 모듈의 소유자 이름. */
    public static final String LICENSE = "license";

    private static final Map<String, Set<String>> BY_OWNER = Map.of(
            ORDER, Set.of(
                    EventType.ORDER_PAID,
                    EventType.LICENSE_SUSPENSION_REQUESTED,
                    EventType.LICENSE_RESTORATION_REQUESTED,
                    EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED),
            LICENSE, Set.of(
                    EventType.LICENSE_GRANTED,
                    EventType.LICENSE_SUSPENDED,
                    EventType.LICENSE_RESTORED,
                    EventType.LICENSE_APPROVAL_CORRECTED));

    private OutboxRepublishTargets() {
    }

    /**
     * {@code owner} 모듈이 재발행할 수 있는 이벤트 타입.
     *
     * @throws IllegalArgumentException 목록에 없는 모듈이면(조립 단계에서 기동을 막는다)
     */
    public static Set<String> forOwner(String owner) {
        Set<String> types = owner == null ? null : BY_OWNER.get(owner);
        if (types == null) {
            throw new IllegalArgumentException("재발행 소유 모듈을 알 수 없다(stove.messaging.ops.republish-owner): " + owner);
        }
        return types;
    }
}
