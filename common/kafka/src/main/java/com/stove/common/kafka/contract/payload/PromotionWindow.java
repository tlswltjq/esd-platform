package com.stove.common.kafka.contract.payload;

import java.time.Instant;

/**
 *  Published pricing schedule; intervals are [startsAt, endsAt).
 *
 * @param id 저장된 대상을 구별하는 식별자.
 * @param discountPerUnit 상품 한 개에 적용하는 할인액.
 * @param bearer 할인 비용을 부담하는 판매자 또는 플랫폼.
 * @param startsAt 할인 적용을 시작하는 시각으로, 경계 시각을 포함.
 * @param endsAt 할인 적용을 종료하는 시각으로, 경계 시각을 제외.
 */
public record PromotionWindow(Long id, long discountPerUnit, String bearer,
                              Instant startsAt, Instant endsAt) {
    public boolean activeAt(Instant instant) {
        return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
    }
}
