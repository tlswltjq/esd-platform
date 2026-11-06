package com.stove.license.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRegistrationRepository;
import com.stove.license.core.domain.LicenseRegistrations;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.PurchaseRefund;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Order가 확정한 환불 완료로 구매 근거와 보유권을 끝낸다. 끝난 구매는 더 바뀌지 않는다(결정 96·97). */
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyPurchaseRefund {
    public static final String CONSUMER_GROUP = "license-purchase-refund";
    private final LicenseRepository licenses;
    private final LicenseRegistrationRepository registrations;
    private final ProcessedEventGuard inbox;
    private final EntityManager entityManager;
    private final Clock clock;

    public void execute(PurchaseRefund input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST, "환불 완료 근거가 필요합니다.");
        License license = licenses.lockByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!license.getLicenseId().equals(input.licenseId()) || !license.getBuyerSubject().equals(input.buyerSubject())
                || !license.getProductId().equals(input.productId()) || !license.getProductCode().equals(input.productCode())
                || !license.getPaymentId().equals(input.paymentId()))
            throw new BusinessException(ErrorCode.CONFLICT, "환불 완료 대상의 구매 근거가 다릅니다.");
        boolean changed = license.refund(input.suspensionRequestEventId(), LicenseRegistrations.current(registrations, license), clock.instant());
        boolean first = inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.PURCHASE_REFUNDED);
        if (changed != first) throw new BusinessException(ErrorCode.CONFLICT, "환불 완료 기록과 Inbox가 다릅니다.");
        entityManager.flush();
    }
}
