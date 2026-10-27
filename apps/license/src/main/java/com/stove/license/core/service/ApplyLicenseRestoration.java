package com.stove.license.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.LicenseRestoredEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseSuspension;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.LicenseRestoration;
import com.stove.license.core.domain.LicenseRestorationRepository;
import com.stove.license.core.domain.RestorationRequest;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyLicenseRestoration {
    public static final String CONSUMER_GROUP = "license-restoration";
    private final LicenseRepository licenses;
    private final LicenseSuspensionRepository suspensions;
    private final LicenseRestorationRepository restorations;
    private final ProcessedEventGuard inbox;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final Clock clock;

    public void execute(RestorationRequest input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        License license = licenses.lockByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!license.getLicenseId().equals(input.licenseId()) || !license.getBuyerSubject().equals(input.buyerSubject())
                || !license.getProductId().equals(input.productId()) || !license.getProductCode().equals(input.productCode())
                || !license.getPaymentId().equals(input.paymentId())) throw new BusinessException(ErrorCode.CONFLICT, "복구 대상의 구매 근거가 다릅니다.");
        Optional<LicenseRestoration> previous = restorations.findByRequestEventId(input.eventId());
        if (previous.isPresent()) {
            previous.orElseThrow().verify(input);
            if (inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_RESTORATION_REQUESTED))
                throw new BusinessException(ErrorCode.CONFLICT, "복구 이력에 해당하는 Inbox가 없습니다.");
            return;
        }
        LicenseSuspension suspension = suspensions.findByRequestEventId(input.suspensionRequestEventId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "복구에 해당하는 실제 정지 이력이 없습니다."));
        if (!suspension.getLicenseId().equals(license.getLicenseId()) || !suspension.getRefundId().equals(input.refundId())
                || !suspension.getCompletedEventId().equals(input.suspensionEventId()) || input.occurredAt().isBefore(suspension.getSuspendedAt()))
            throw new BusinessException(ErrorCode.CONFLICT, "복구 요청의 환불·실제 정지 근거가 다릅니다.");
        if (!inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_RESTORATION_REQUESTED))
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox에 해당하는 복구 이력이 없습니다.");
        license.restore(input.suspensionRequestEventId());
        LicenseRestoration result = LicenseRestoration.record(license, input, clock.instant()); restorations.save(result);
        outbox.record("License", license.getLicenseId(), new LicenseRestoredEvent(result.getCompletedEventId(), result.getRestoredAt(),
                input.eventId(), input.refundId(), input.attemptId(), input.failureEventId(), input.licenseId(), input.orderNo(),
                input.buyerSubject(), input.productId(), input.productCode(), input.paymentId(), input.suspensionRequestEventId(), input.suspensionEventId()));
        entityManager.flush();
    }
}
