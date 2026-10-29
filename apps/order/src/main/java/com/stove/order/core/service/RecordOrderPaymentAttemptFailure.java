package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.OrderPaymentAdmission;
import com.stove.order.core.domain.OrderPaymentAdmissionRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.PaymentAdmissionFailureInput;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(isolation=Isolation.READ_COMMITTED)
public class RecordOrderPaymentAttemptFailure {
    private final OrderRepository orders;
    private final OrderPaymentAdmissionRepository admissions;
    private final Clock clock;
    private final RecordOrderExpiry expiry;
    public RecordOrderPaymentAttemptFailure(OrderRepository orders,OrderPaymentAdmissionRepository admissions,Clock clock,RecordOrderExpiry expiry) {
        this.orders=orders; this.admissions=admissions; this.clock=clock; this.expiry=expiry;
    }
    public OrderPaymentAdmission execute(PaymentAdmissionFailureInput input) {
        if (input==null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Order order=orders.lockByOrderNo(input.orderNo()).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        OrderPaymentAdmission receipt=admissions.findByAdmissionId(input.admissionId()).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        receipt.requireFailure(input);
        if (receipt.getClosedAt()!=null) return receipt;
        OrderPaymentAdmission latest=admissions.findFirstByOrderNoOrderByIdDesc(input.orderNo()).orElseThrow();
        Instant at=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!latest.getAdmissionId().equals(input.admissionId()) || input.failedAt().isAfter(at))
            throw new BusinessException(ErrorCode.CONFLICT,"Only the current verified failure may release processing");
        order.releaseFailedPaymentProcessing(input.paymentId(),at);
        receipt.closeFailure(input,order);
        if(order.getStatus()==OrderStatus.EXPIRED)expiry.execute(order);
        return receipt;
    }
}
