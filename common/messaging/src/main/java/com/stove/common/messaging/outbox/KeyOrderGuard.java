package com.stove.common.messaging.outbox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 잠근 배치에서 <b>같은 키의 앞 미발행 행이 모두 같은 배치에 있는 행</b>만 내보낸다([결정 155] 발행 쪽, PR #203 R1).
 *
 * <p>{@link OutboxEventRepository#lockPendingBatch}의 하위 조회는 잠금을 보지 않는다. 그래서 다른 트랜잭션
 * (예: 운영 재발행의 {@code lockByEventId})이 앞 행을 잠그고 있으면 바깥 {@code SKIP LOCKED}는 앞 행을 건너뛰고
 * 하위 조회는 앞 행을 "지금 나갈 수 있음"으로 보아 뒤 행이 먼저 나갈 수 있다. 이 거름이 그 틈을 닫는다.
 * 걸러진 행은 {@code PENDING} 그대로 남아 다음 폴링에서 다시 판정된다.
 */
final class KeyOrderGuard {

    private KeyOrderGuard() {
    }

    /**
     * @param batch  잠근 배치({@code id} 오름차순)
     * @param unsent 배치에 든 키들의 미발행 행 위치. 배치의 가장 큰 {@code id}까지면 충분하다.
     * @return 내보내도 되는 행({@code id} 오름차순)
     */
    static List<OutboxEvent> releasable(List<OutboxEvent> batch, List<OutboxKeyPosition> unsent) {
        Set<Long> inBatch = new HashSet<>();
        for (OutboxEvent event : batch) {
            inBatch.add(event.getId());
        }
        Map<String, Long> firstMissing = new HashMap<>();
        List<OutboxKeyPosition> ordered = new ArrayList<>(unsent);
        ordered.sort(Comparator.comparing(OutboxKeyPosition::id));
        for (OutboxKeyPosition position : ordered) {
            if (!inBatch.contains(position.id())) {
                firstMissing.putIfAbsent(position.partitionKey(), position.id());
            }
        }
        List<OutboxEvent> released = new ArrayList<>();
        for (OutboxEvent event : batch) {
            Long missing = firstMissing.get(event.getPartitionKey());
            if (missing == null || event.getId() < missing) {
                released.add(event);
            }
        }
        return released;
    }
}
