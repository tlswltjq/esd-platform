package com.stove.payment.core.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 정책 값. 어댑터 설정이 아니라 <b>도메인 규칙의 계수</b>라 {@code core.domain} 에 둔다.
 * 네 값이 각각 무엇을 지키는지, 왜 합칠 수 없는지는 docs/code-notes.md
 */
@ConfigurationProperties(prefix = "stove.payment")
public record PaymentProperties(Duration window, Duration checkoutWindow,
                                Duration refundResumeAfter, Duration refundBudget) {

    public PaymentProperties {
        window = window == null ? Duration.ofMinutes(30) : window;
        checkoutWindow = checkoutWindow == null ? Duration.ofMinutes(15) : checkoutWindow;
        refundResumeAfter = refundResumeAfter == null ? Duration.ofMinutes(2) : refundResumeAfter;
        refundBudget = refundBudget == null ? Duration.ofHours(1) : refundBudget;
    }
}
