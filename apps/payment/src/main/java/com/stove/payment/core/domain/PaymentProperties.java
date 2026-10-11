package com.stove.payment.core.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 정책 값. 어댑터 설정이 아니라 <b>도메인 규칙의 계수</b>라 {@code core.domain} 에 둔다.
 * 결제 준비·승인 대기·환불 복구·재시도의 기간을 각각 지정한다.
 *
 * @param window 주문 생성 후 결제를 준비할 수 있는 기간.
 * @param checkoutWindow 결제 준비 후 승인을 기다리는 결제창 유효 기간.
 * @param refundResumeAfter 진행 중인 환불을 중단된 작업으로 보고 재개하기까지의 기간.
 * @param refundBudget 환불 자동 재시도를 계속할 수 있는 총 기간.
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
