package com.stove.payment.core.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 정책 값.
 *
 * <p>{@code config} 가 아니라 {@code core.domain} 에 두는 이유는 정산 수수료율과 같다 —
 * 이건 어댑터 설정이 아니라 <b>도메인 규칙의 계수</b>다. 설정 패키지로 빼면 {@code core → config}
 * 의존이 생긴다({@code ModulePackageRules} 참고).
 *
 * @param window 주문이 만들어진 뒤 결제를 시작할 수 있는 시간. 이 시간이 지나면 사전등록이 막히고,
 *               사용자는 다시 주문해야 한다 — 그러면 가격이 <b>지금 가격으로 다시 확정된다.</b>
 *               기본 30분은 장바구니 성격의 주문이 가격 변동을 넘겨 살아남지 않을 만큼 짧고,
 *               결제창을 띄워 둔 사용자가 쫓겨나지 않을 만큼 길게 잡은 값이다.
 */
@ConfigurationProperties(prefix = "stove.payment")
public record PaymentProperties(Duration window) {

    public PaymentProperties {
        window = window == null ? Duration.ofMinutes(30) : window;
    }
}
