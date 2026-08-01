package com.stove.payment.application;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.api.dto.PgCallbackRequest;
import com.stove.payment.api.dto.PreparePaymentResponse;
import com.stove.payment.domain.Payment;
import com.stove.payment.domain.PaymentRepository;
import com.stove.payment.infrastructure.pg.PgClient;
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

    private final PaymentRepository paymentRepository;
    private final OutboxRecorder outboxRecorder;
    private final PgClient pgClient;

    /** OrderCreated 수신 시 결제 대기 레코드 생성 (주문번호 유니크로 중복 생성 차단) */
    public void createReady(String orderNo, Long memberId, long amount, String currency, List<OrderLine> lines) {
        if (paymentRepository.existsByOrderNo(orderNo)) {
            log.info("결제 대기 레코드 이미 존재 orderNo={}", orderNo);
            return;
        }
        paymentRepository.save(Payment.ready(orderNo, memberId, amount, currency, lines));
    }

    /** 게이트 2: PG 사전등록 */
    public PreparePaymentResponse prepare(String orderNo, String method) {
        Payment payment = findPayment(orderNo);
        PgClient.PgPrepareResult result =
                pgClient.prepare(orderNo, payment.getAmount(), payment.getCurrency(), method);
        payment.prepare(result.pgTxId(), method);

        return new PreparePaymentResponse(orderNo, result.pgTxId(),
                payment.getAmount(), payment.getCurrency(), result.redirectUrl());
    }

    /**
     * 게이트 3+4: PG 콜백 처리.
     * 금액 대조 실패 시 승인 확정하지 않고 예외 → 운영 알람 대상.
     * 중복 콜백은 상태/멱등키로 흡수하고 이벤트를 재발행하지 않는다.
     */
    public void handleCallback(PgCallbackRequest request) {
        Payment payment = paymentRepository.findByIdempotencyKey(request.idempotencyKey())
                .orElseGet(() -> findPayment(request.orderNo()));

        boolean approved = payment.approve(request.pgTxId(), request.paidAmount(), request.idempotencyKey());
        if (!approved) {
            log.info("중복 결제 콜백 무시 orderNo={} key={}", request.orderNo(), request.idempotencyKey());
            return;
        }

        outboxRecorder.record(AGGREGATE, payment.getOrderNo(),
                PaymentCompletedEvent.of(payment.getId(), payment.getOrderNo(), payment.getMemberId(),
                        payment.getAmount(), payment.getMethod(), payment.getLines()));

        log.info("결제 승인 orderNo={} amount={}", payment.getOrderNo(), payment.getAmount());
    }

    /**
     * 환불. 사용자 요청 환불과 Saga 보상 트랜잭션(LicenseIssueFailed)이 같은 경로를 쓴다.
     */
    public void cancel(String orderNo, String reason) {
        Payment payment = findPayment(orderNo);
        if (!payment.cancel(reason)) {
            log.info("이미 취소된 결제 orderNo={}", orderNo);
            return;
        }
        pgClient.cancel(payment.getPgTxId(), payment.getAmount(), reason);

        outboxRecorder.record(AGGREGATE, orderNo,
                PaymentCancelledEvent.of(payment.getId(), orderNo, payment.getMemberId(),
                        payment.getAmount(), reason));

        log.info("결제 취소 orderNo={} reason={}", orderNo, reason);
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
