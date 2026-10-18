package com.stove.license.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.PaidOrder;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class GrantLicense {
    public static final String CONSUMER_GROUP = "license-paid-order";
    private final LicenseRepository licenses;
    private final ProcessedEventGuard inbox;
    private final EntityManager entityManager;

    public void execute(PaidOrder order) {
        Optional<License> previous = licenses.findByOrderNo(order.orderNo());
        if (previous.isPresent()) {
            verifyRedelivery(previous.get(), order);
            return;
        }
        if (!licenses.findSourceConflicts(order.eventId(), order.requestEventId(),
                order.paymentResultEventId(), order.paymentId()).isEmpty())
            throw conflict("이벤트·요청·결제 식별자가 다른 주문에 사용되었습니다.");
        if (!licenses.findProductConflicts(order.buyerSubject(), order.productId(), order.productCode()).isEmpty())
            throw conflict("다른 주문으로 이미 지급된 상품입니다.");
        if (!inbox.firstDelivery(order.eventId(), CONSUMER_GROUP, EventType.ORDER_PAID))
            throw conflict("Inbox에 해당하는 구매 근거가 없습니다.");

        licenses.save(License.grant(order));
        entityManager.flush();
    }

    private void verifyRedelivery(License previous, PaidOrder order) {
        previous.verify(order);
        if (inbox.firstDelivery(order.eventId(), CONSUMER_GROUP, EventType.ORDER_PAID))
            throw conflict("구매 근거에 해당하는 Inbox가 없습니다.");
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
