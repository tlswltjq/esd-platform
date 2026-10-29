package com.stove.order.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.OrderPaymentAdmission;
import com.stove.order.core.domain.OrderPaymentAdmissionRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.PaymentAdmissionInput;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(isolation = Isolation.READ_COMMITTED)
public class RequestOrderPaymentProcessing {
    private final OrderRepository orders;
    private final OrderPaymentAdmissionRepository admissions;
    private final Clock clock;
    private final RecordOrderExpiry expiry;

    public RequestOrderPaymentProcessing(OrderRepository orders, OrderPaymentAdmissionRepository admissions, Clock clock,RecordOrderExpiry expiry) {
        this.orders = orders; this.admissions = admissions; this.clock = clock; this.expiry=expiry;
    }

    public Optional<OrderPaymentAdmission> execute(PaymentAdmissionInput input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Order order = orders.lockByOrderNo(input.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Optional<OrderPaymentAdmission> previous = admissions.findByAdmissionId(input.admissionId());
        if (previous.isPresent()) {
            previous.get().requireSameInput(input);
            return previous;
        }
        // Observe the deadline after obtaining the same lock used by expiry and result application.
        OrderStatus before=order.getStatus();
        if (!order.beginPaymentProcessing(input, clock.instant().truncatedTo(ChronoUnit.MICROS))) {
            if(before==OrderStatus.PENDING && order.getStatus()==OrderStatus.EXPIRED)expiry.execute(order);
            return Optional.empty();
        }
        try {
            return Optional.of(admissions.saveAndFlush(OrderPaymentAdmission.record(order, input)));
        } catch (DataIntegrityViolationException collision) {
            throw new BusinessException(ErrorCode.CONFLICT, "승인 진행 식별자가 이미 사용되었습니다.");
        }
    }
}
