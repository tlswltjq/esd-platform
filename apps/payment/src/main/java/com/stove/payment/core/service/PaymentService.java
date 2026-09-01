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
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.domain.PgDecline;
import com.stove.payment.core.domain.PgPreparation;
import com.stove.payment.core.domain.RefundRetryPolicy;
import com.stove.payment.core.domain.StrandedCancellation;
import com.stove.payment.core.port.PgClient;
import java.time.Duration;
import java.time.Instant;
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

    /** 게이트 2: PG 사전등록. <b>만료 검사가 PG 호출보다 먼저여야 한다</b> — docs/code-notes.md */
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
     * 게이트 3+4: PG 콜백 처리. 만료 뒤 승인은 거절하지 않고 받아 적은 뒤 되돌리며,
     * 이때 {@code PaymentCompleted} 를 <b>내보내지 않는다.</b> 근거는 docs/code-notes.md
     *
     * @return PG 환불이 필요하면 그 값, 아니면 {@link PaymentCancellation#none()}
     */
    public PaymentCancellation handleApproval(PgApproval approval) {
        // 멱등키가 아니라 주문번호로 찾고, 행을 잠그고 읽는다. [D-008]
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

    /** PG 승인 거절 콜백 처리. 승인과 <b>같은 행 잠금</b>을 쓴다 — docs/code-notes.md */
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
     * 취소 1단계: 의도를 커밋한다. <b>PG 환불을 이 트랜잭션 안에서 부르면 안 된다.</b>
     *
     * @return PG 환불이 필요 없으면 {@link PaymentCancellation#none()}
     */
    public PaymentCancellation beginCancel(String orderNo, String reason) {
        Payment payment = findPayment(orderNo);
        boolean firstEntry = payment.getStatus() != PaymentStatus.CANCELING;
        if (!payment.beginCancel(reason)) {
            log.info("이미 취소된 결제 orderNo={}", orderNo);
            return PaymentCancellation.none();
        }
        // 최초 진입에서만 유예를 준다 — 재개 경로는 스윕이 이미 백오프를 걸어 두었다.
        if (firstEntry) {
            payment.scheduleFirstCancelRetry(paymentProperties.refundResumeAfter());
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

    /** Saga 보상 환불 진입점. 사용자 환불과 나눈 이유는 docs/code-notes.md */
    public PaymentCancellation beginCompensation(String eventId, String eventType,
                                                 String orderNo, String reason) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return PaymentCancellation.none();
        }
        Payment payment = paymentRepository.findByOrderNo(orderNo).orElse(null);
        if (payment == null) {
            // 예외를 던지면 가드 마킹이 롤백되어 같은 이벤트가 영원히 돌아온다.
            log.error("보상 대상 결제 없음 — 수동 확인 필요 orderNo={} reason={}", orderNo, reason);
            return PaymentCancellation.none();
        }
        if (!payment.cancelable()) {
            // 같은 이유로 던지지 않는다 — 소비는 진행시키되 사람이 볼 수 있게 남긴다.
            log.error("보상 대상 결제 상태 불일치 — 수동 확인 필요 orderNo={} status={} reason={}",
                    orderNo, payment.getStatus(), reason);
            return PaymentCancellation.none();
        }
        log.warn("라이선스 지급 실패 → 보상 환불 실행 orderNo={} reason={}", orderNo, reason);
        return beginCancel(orderNo, reason);
    }

    /** 취소 착수는 커밋됐는데 확정까지 못 간 건들. 엔티티가 아니라 값으로 돌려준다. */
    @Transactional(readOnly = true)
    public List<StrandedCancellation> findStrandedCancellations() {
        return paymentRepository
                .findDueForCancelRetry(PaymentStatus.CANCELING, Instant.now())
                .stream()
                .map(payment -> new StrandedCancellation(
                        payment.getOrderNo(), payment.getCancelReason(), payment.getCancelAttempts()))
                .toList();
    }

    /** 다음 재개 시도를 예약한다. <b>성공하든 실패하든</b> 부른다 — docs/code-notes.md */
    @Transactional
    public void scheduleCancelRetry(String orderNo) {
        paymentRepository.findByOrderNo(orderNo).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.CANCELING) {
                payment.scheduleCancelRetry(RefundRetryPolicy.backoffAfter(payment.getCancelAttempts()));
            }
        });
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
