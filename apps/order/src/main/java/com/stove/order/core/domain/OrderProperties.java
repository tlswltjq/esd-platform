package com.stove.order.core.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 주문 정책 값.
 *
 * <p>{@code config} 가 아니라 {@code core.domain} 에 두는 이유는 payment 쪽과 같다 —
 * 어댑터 설정이 아니라 <b>도메인 규칙의 계수</b>다.
 *
 * @param expireAfter 결제를 시작하지 않은 주문을 이만큼 지나면 만료시킨다.
 *        <p><b>payment 의 {@code window}(30분)보다 길어야 한다.</b> 그쪽이 짧으면 아직 결제할 수
 *        있는 주문을 order 가 먼저 닫아 버려서, 사용자는 결제창을 열 수 있는데 주문이 없는 상태를 본다.
 *        기본 1시간은 그 창의 두 배로, <b>두 서비스의 시계가 조금 어긋나도 순서가 뒤집히지 않는
 *        폭</b>이다. 반대로 너무 길면 만료가 정리 기능으로서 의미를 잃는다.
 * @param expireBatchSize 한 회차에 만료시킬 최대 건수.
 *        <p>이 값이 없으면 첫 회차가 밀린 것을 전부 집는다 — 실측 98,750건이었고, 한 트랜잭션에
 *        넣으면 락과 언두 로그가 그만큼 커진다. <b>밀린 것을 한 번에 없애는 것이 목적이 아니라,
 *        늘지 않게 하는 것이 목적이다.</b> 기본 500은 1분 주기와 곱해 시간당 3만 건으로,
 *        실측된 유입(하루 1~3건)의 몇 자릿수 위다.
 */
@ConfigurationProperties(prefix = "stove.order")
public record OrderProperties(Duration expireAfter, int expireBatchSize) {

    public OrderProperties {
        expireAfter = expireAfter == null ? Duration.ofHours(1) : expireAfter;
        expireBatchSize = expireBatchSize <= 0 ? 500 : expireBatchSize;
    }
}
