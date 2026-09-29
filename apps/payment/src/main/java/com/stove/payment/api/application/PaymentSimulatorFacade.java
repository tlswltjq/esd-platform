package com.stove.payment.api.application;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.service.PaymentService;
import com.stove.payment.core.service.SimulatedPgService;
import com.stove.payment.core.domain.SimulatedPgTransaction;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 데모 PG가 확인한 결과만 기존 결제 콜백 유스케이스로 전달한다. */
@Service
@Profile("!prod & (demo | test)")
@RequiredArgsConstructor
public class PaymentSimulatorFacade {

    private final PaymentService paymentService;
    private final PaymentCallbackFacade callbackFacade;
    private final RefundFacade refundFacade;
    private final SimulatedPgService pgClient;

    public SimulatedPgTransaction checkout(String pgTxId, Long memberId) {
        SimulatedPgTransaction transaction = pgClient.get(pgTxId);
        Payment payment = paymentService.getPaymentForMember(transaction.getOrderNo(), memberId);
        requireCurrent(payment, transaction);
        return transaction;
    }

    public Payment approve(String orderNo) {
        Payment payment = currentPayment(orderNo);
        callbackFacade.approve(pgClient.approve(payment.getPgTxId()));
        return paymentService.getPayment(orderNo);
    }

    public Payment decline(String orderNo, String code, String reason) {
        Payment payment = currentPayment(orderNo);
        paymentService.handleDecline(pgClient.decline(payment.getPgTxId(), code, reason));
        return paymentService.getPayment(orderNo);
    }

    public Payment timeout(String orderNo) {
        Payment payment = currentPayment(orderNo);
        paymentService.handleDecline(pgClient.timeout(payment.getPgTxId()));
        return paymentService.getPayment(orderNo);
    }

    public Payment refund(String orderNo) {
        currentPayment(orderNo);
        refundFacade.refund(orderNo, "SIMULATED_REFUND");
        return paymentService.getPayment(orderNo);
    }

    private Payment currentPayment(String orderNo) {
        Payment payment = paymentService.getPayment(orderNo);
        if (payment.getPgTxId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제 사전등록이 필요합니다.");
        }
        requireCurrent(payment, pgClient.get(payment.getPgTxId()));
        return payment;
    }

    private static void requireCurrent(Payment payment, SimulatedPgTransaction transaction) {
        if (!payment.getOrderNo().equals(transaction.getOrderNo())
                || !Objects.equals(payment.getPgTxId(), transaction.getPgTxId())
                || payment.getAmount() != transaction.getAmount()
                || !payment.getCurrency().equals(transaction.getCurrency())) {
            throw new BusinessException(ErrorCode.CONFLICT, "사전등록 결제 정보가 일치하지 않습니다.");
        }
    }
}
