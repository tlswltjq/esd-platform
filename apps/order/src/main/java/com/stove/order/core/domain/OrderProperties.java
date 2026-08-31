package com.stove.order.core.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 주문 정책 값. 어댑터 설정이 아니라 <b>도메인 규칙의 계수</b>라 {@code core.domain} 에 둔다.
 *
 * @param expireAfter <b>payment 의 {@code window}(30분)보다 길어야 한다</b> —
 *        짧으면 아직 결제할 수 있는 주문을 order 가 먼저 닫는다. docs/code-notes.md
 * @param expireBatchSize 한 회차에 만료시킬 최대 건수.
 *        밀린 것을 한 번에 없애는 것이 아니라 <b>늘지 않게 하는 것이 목적이다.</b> docs/code-notes.md
 */
@ConfigurationProperties(prefix = "stove.order")
public record OrderProperties(Duration expireAfter, int expireBatchSize) {

    public OrderProperties {
        expireAfter = expireAfter == null ? Duration.ofHours(1) : expireAfter;
        expireBatchSize = expireBatchSize <= 0 ? 500 : expireBatchSize;
    }
}
