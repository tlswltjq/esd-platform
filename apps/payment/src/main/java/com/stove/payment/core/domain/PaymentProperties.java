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
 * <p><b>시계가 둘인 이유</b> — 지키는 것이 다르다. {@code window} 는 <i>옛 가격이 유효한 기간</i>이고
 * {@code checkoutWindow} 는 <i>사용자가 카드번호를 넣는 데 주는 시간</i>이다. 하나로 합치면
 * 주문 창이 30분일 때 29분째에 결제창을 연 사용자에게 1분만 주게 되고, 그걸 피하려고 창을 넓히면
 * 이번에는 옛 가격이 오래 유효해진다. <b>한쪽을 고치면 다른 쪽이 나빠지는 값은 같은 값이 아니다.</b>
 *
 * @param window 주문이 만들어진 뒤 결제를 <b>시작</b>할 수 있는 시간. 지나면 사전등록이 막히고
 *               사용자는 다시 주문해야 한다 — 그러면 가격이 <b>지금 가격으로 다시 확정된다.</b>
 *               기본 30분은 장바구니 성격의 주문이 가격 변동을 넘겨 살아남지 않을 만큼 짧게 잡은 값이다.
 * @param checkoutWindow 사전등록 뒤 승인 콜백까지 허용하는 시간. 지나서 온 승인은
 *               <b>거절하지 않고 받아 적은 뒤 자동 환불한다</b> — 그 시점엔 PG 에서 이미 돈이
 *               움직였으므로 거절은 대사를 깨뜨린다. 기본 15분은 실 PG 결제창 세션(보통 10~30분)
 *               안쪽에 두어, <b>대개는 PG 가 먼저 만료시키고 우리 검사는 그물의 두 번째 겹</b>이 되게 한 값이다.
 * @param refundResumeAfter 취소 착수({@code CANCELING})가 이만큼 지나도 확정되지 않으면
 *               중단된 것으로 보고 재개한다. <b>돈이 나갔는지 불확실한 상태를 시간으로 끝내는 값</b>이다.
 *               정상 환불은 PG 왕복 한 번이라 초 단위로 끝나므로, 진행 중인 건을 옆에서 다시 부르지
 *               않을 만큼만 여유를 둔다(기본 2분).
 */
@ConfigurationProperties(prefix = "stove.payment")
public record PaymentProperties(Duration window, Duration checkoutWindow, Duration refundResumeAfter) {

    public PaymentProperties {
        window = window == null ? Duration.ofMinutes(30) : window;
        checkoutWindow = checkoutWindow == null ? Duration.ofMinutes(15) : checkoutWindow;
        refundResumeAfter = refundResumeAfter == null ? Duration.ofMinutes(2) : refundResumeAfter;
    }
}
