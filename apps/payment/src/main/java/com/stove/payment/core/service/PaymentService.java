package com.stove.payment.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.event.payload.PaymentFailedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentCancellation;
import com.stove.payment.core.domain.PaymentMetrics;
import com.stove.payment.core.domain.PaymentPreparation;
import com.stove.payment.core.domain.PaymentProperties;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.domain.PgDecline;
import com.stove.payment.core.domain.PgPreparation;
import com.stove.payment.core.port.PgClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 유스케이스.
 * 승인/취소 확정과 이벤트 적재(Outbox)는 항상 같은 트랜잭션에서 일어난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

    private static final String AGGREGATE = "Payment";

    /** Kafka 컨슈머 그룹이자 Inbox 멱등 키. 리스너도 이 상수를 참조한다 — {@code ConsumerGroupRules} 참고. */
    public static final String CONSUMER_GROUP = "payment";

    private final PaymentRepository paymentRepository;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;
    private final PgClient pgClient;
    private final PaymentProperties paymentProperties;
    private final PaymentMetrics paymentMetrics;

    /** OrderCreated 수신 시 결제 대기 레코드 생성 (주문번호 유니크로 중복 생성 차단) */
    public void createReady(String eventId, String eventType, String orderNo, Long memberId,
                            long amount, String currency, List<OrderLine> lines) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        if (paymentRepository.existsByOrderNo(orderNo)) {
            log.info("결제 대기 레코드 이미 존재 orderNo={}", orderNo);
            return;
        }
        paymentRepository.save(Payment.ready(orderNo, memberId, amount, currency, lines));
        log.info("결제 대기 생성 orderNo={} amount={}", orderNo, amount);
    }

    /**
     * 게이트 2: PG 사전등록.
     *
     * <p>만료 검사를 <b>PG 를 부르기 전에</b> 한다. 뒤에 두면 만료된 주문에도 PG 거래가 하나 생기고,
     * 그건 우리 장부에는 없는데 PG 에는 있는 상태다 — 대사에서 원인을 알 수 없는 잔여로 남는다.
     */
    public PaymentPreparation prepare(String orderNo, String method) {
        Payment payment = findPayment(orderNo);
        payment.requireWithinWindow(paymentProperties.window());

        PgPreparation result =
                pgClient.prepare(orderNo, payment.getAmount(), payment.getCurrency(), method);
        payment.prepare(result.pgTxId(), method, paymentProperties.window());

        return new PaymentPreparation(orderNo, result.pgTxId(),
                payment.getAmount(), payment.getCurrency(), result.redirectUrl());
    }

    /** 결제창이 만료된 뒤 도착한 승인을 자동으로 되돌릴 때 남기는 사유. 지표 태그이자 이벤트 사유다. */
    public static final String CHECKOUT_EXPIRED = "CHECKOUT_WINDOW_EXPIRED";

    /**
     * 게이트 3+4: PG 콜백 처리.
     * 금액 대조 실패 시 승인 확정하지 않고 예외 → 운영 알람 대상.
     * 중복 콜백은 상태/멱등키로 흡수하고 이벤트를 재발행하지 않는다.
     *
     * <p><b>결제창이 만료된 뒤 온 승인은 거절하지 않는다.</b> 거절하려면 예외를 던져야 하는데,
     * 그 시점에는 PG 에서 이미 돈이 움직였다 — 우리 장부에만 없는 상태가 되어
     * <b>대사에서 원인을 알 수 없는 잔여</b>로 남는다. 그래서 승인을 적고 곧바로 되돌린다.
     *
     * <p>이때 {@code PaymentCompleted} 를 <b>내보내지 않는다.</b> 내보내면 license 가 지급하고
     * settlement 가 매출을 적은 뒤 곧이어 둘 다 되돌리게 된다 — 사용자에게는 게임이 잠깐 생겼다
     * 사라지고, 원장에는 매출과 상계가 한 쌍 남는다. <b>일어나지 않을 판매를 알리지 않는다.</b>
     * 하위 서비스는 뒤이은 {@code PaymentCancelled} 만 받고, 셋 다 "되돌릴 것이 없다"로 정상 종료한다
     * (order 는 {@code CREATED} 에서 취소, license 는 라이선스 없음, settlement 는 매출 원장 없음).
     *
     * <p>PG 환불 호출은 여기 없다 — 되돌릴 수 없는 외부 호출이라 트랜잭션 밖이어야 한다.
     * 순서는 {@link com.stove.payment.api.application.PaymentCallbackFacade} 가 잡는다.
     *
     * @return PG 환불이 필요하면 그 값, 아니면 {@link PaymentCancellation#none()}
     */
    public PaymentCancellation handleApproval(PgApproval approval) {
        // 주문번호로 찾는다. 멱등키는 PG 가 만드는 값이라 재사용되면 다른 주문의 결제를 물어온다(D-008).
        // 행을 잠그고 읽어 동시에 들어온 중복 콜백이 둘 다 승인되는 창을 닫는다.
        Payment payment = paymentRepository.findByOrderNoForUpdate(approval.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "orderNo=" + approval.orderNo()));

        boolean approved = payment.approve(approval.pgTxId(), approval.paidAmount(), approval.idempotencyKey());
        if (!approved) {
            log.info("중복 결제 콜백 무시 orderNo={} key={}", approval.orderNo(), approval.idempotencyKey());
            return PaymentCancellation.none();
        }

        if (payment.checkoutExpired(paymentProperties.checkoutWindow())) {
            payment.beginCancel(CHECKOUT_EXPIRED);
            paymentMetrics.recordAutoRefund(CHECKOUT_EXPIRED);
            log.warn("결제창 만료 후 도착한 승인 — 받아 적고 자동 환불한다 orderNo={} amount={} 창={}",
                    payment.getOrderNo(), payment.getAmount(), paymentProperties.checkoutWindow());
            return PaymentCancellation.of(payment.getPgTxId(), payment.getAmount());
        }

        outboxRecorder.record(AGGREGATE, payment.getOrderNo(),
                PaymentCompletedEvent.of(payment.getId(), payment.getOrderNo(), payment.getMemberId(),
                        payment.getAmount(), payment.getMethod(), payment.getLines()));

        log.info("결제 승인 orderNo={} amount={}", payment.getOrderNo(), payment.getAmount());
        return PaymentCancellation.none();
    }

    /**
     * PG 승인 거절 콜백 처리.
     *
     * <p>승인과 <b>같은 행 잠금</b>을 쓴다. 승인 콜백과 거절 콜백이 동시에 도착하면 잠금이 순서를
     * 강제하고, 뒤에 오는 쪽이 상태 가드에 걸려 예외로 뜬다 — 어느 순서든 엇갈린 콜백이
     * 조용히 흡수되지 않는다.
     *
     * <p>중복 거절은 종단 상태로 흡수하고 이벤트를 재발행하지 않는다.
     */
    public void handleDecline(PgDecline decline) {
        Payment payment = paymentRepository.findByOrderNoForUpdate(decline.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "orderNo=" + decline.orderNo()));

        boolean failed = payment.fail(decline.pgTxId(), decline.reasonCode(), decline.reason());
        if (!failed) {
            log.info("중복 거절 콜백 무시 orderNo={} code={}", decline.orderNo(), decline.reasonCode());
            return;
        }

        outboxRecorder.record(AGGREGATE, payment.getOrderNo(),
                PaymentFailedEvent.of(payment.getId(), payment.getOrderNo(), payment.getMemberId(),
                        decline.reasonCode(), decline.reason()));

        log.warn("결제 승인 거절 orderNo={} code={} reason={}",
                payment.getOrderNo(), decline.reasonCode(), decline.reason());
    }

    /**
     * 취소 1단계: 의도를 커밋한다.
     *
     * <p>PG 환불은 되돌릴 수 없으므로 이 트랜잭션 안에서 부르지 않는다. 부르면 뒤이은 적재나 커밋이
     * 실패했을 때 "돈은 나갔는데 장부는 PAID" 가 된다. 실제 호출은
     * {@link com.stove.payment.api.application.RefundFacade} 가 커밋 뒤에 한다.
     *
     * @return PG 환불이 필요 없으면 {@link PaymentCancellation#none()}
     */
    public PaymentCancellation beginCancel(String orderNo, String reason) {
        Payment payment = findPayment(orderNo);
        if (!payment.beginCancel(reason)) {
            log.info("이미 취소된 결제 orderNo={}", orderNo);
            return PaymentCancellation.none();
        }
        log.info("결제 취소 착수 orderNo={} reason={}", orderNo, reason);
        return PaymentCancellation.of(payment.getPgTxId(), payment.getAmount());
    }

    /** 취소 2단계: PG 환불이 끝난 뒤 확정하고 이벤트를 적재한다. */
    public void completeCancel(String orderNo, String reason) {
        Payment payment = findPayment(orderNo);
        payment.completeCancel();

        outboxRecorder.record(AGGREGATE, orderNo,
                PaymentCancelledEvent.of(payment.getId(), orderNo, payment.getMemberId(),
                        payment.getAmount(), reason));

        log.info("결제 취소 orderNo={} reason={}", orderNo, reason);
    }

    /**
     * Saga 보상 환불 진입점. license 지급 최종 실패 이벤트로만 들어온다.
     *
     * <p>사용자 환불과 규칙은 같지만 진입점을 나눈 이유는 멱등키의 출처가 다르기 때문이다 —
     * 이벤트 경로만 중복 수신 마킹이 필요하고, HTTP 경로에는 넘길 eventId 가 없다.
     */
    public PaymentCancellation beginCompensation(String eventId, String eventType,
                                                 String orderNo, String reason) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return PaymentCancellation.none();
        }
        Payment payment = paymentRepository.findByOrderNo(orderNo).orElse(null);
        if (payment == null) {
            // 주문번호가 어긋났거나(연동 오류) 결제 생성 이벤트를 아직 못 받은 상태다.
            // 아래 상태 불일치와 같은 이유로 예외를 던지지 않는다 — 결제는 재시도한다고 생기지 않으므로
            // 던지면 가드 마킹이 롤백되어 같은 이벤트가 영원히 돌아온다.
            log.error("보상 대상 결제 없음 — 수동 확인 필요 orderNo={} reason={}", orderNo, reason);
            return PaymentCancellation.none();
        }
        if (!payment.cancelable()) {
            // 정상 흐름에서는 나올 수 없는 조합이다. 결제가 스스로 PAID 가 될 수는 없으므로
            // 예외를 던지면 가드 마킹까지 롤백되어 같은 이벤트가 영원히 재전송된다(파티션 정지).
            // 소비는 진행시키되 사람이 볼 수 있게 남긴다.
            log.error("보상 대상 결제 상태 불일치 — 수동 확인 필요 orderNo={} status={} reason={}",
                    orderNo, payment.getStatus(), reason);
            return PaymentCancellation.none();
        }
        log.warn("라이선스 지급 실패 → 보상 환불 실행 orderNo={} reason={}", orderNo, reason);
        return beginCancel(orderNo, reason);
    }

    @Transactional(readOnly = true)
    public Payment getPayment(String orderNo) {
        return findPayment(orderNo);
    }

    private Payment findPayment(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND, "orderNo=" + orderNo));
    }
}
