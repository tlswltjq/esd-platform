package com.stove.license.core.service;

import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.LicenseSuspendedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import com.stove.license.core.domain.LicenseSuspension;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.SuspensionRequest;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyLicenseSuspension {
    public static final String CONSUMER_GROUP = "license-suspension";
    private final LicenseRepository licenses;
    private final LicenseSuspensionRepository suspensions;
    private final ProcessedEventGuard inbox;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final Clock clock;

    public void execute(SuspensionRequest input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST, "정지 요청이 필요합니다.");
        License license = licenses.lockByOrderNo(input.orderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        license.verifySuspensionTarget(input);
        Optional<LicenseSuspension> previous = suspensions.findByRequestEventId(input.eventId());
        if (previous.isPresent()) {
            previous.get().verify(license, input);
            if (inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_SUSPENSION_REQUESTED)) {
                throw new BusinessException(ErrorCode.CONFLICT, "정지 처리에 해당하는 Inbox가 없습니다.");
            }
            return;
        }
        if (license.getStatus() != LicenseStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CONFLICT, "다른 요청으로 정지된 보유권입니다.");
        }
        if (!inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.LICENSE_SUSPENSION_REQUESTED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Inbox에 해당하는 정지 처리 근거가 없습니다.");
        }
        license.suspend(input.eventId());
        LicenseSuspension result = LicenseSuspension.record(license, input, clock.instant());
        suspensions.save(result);
        LicenseSuspendedEvent event = new LicenseSuspendedEvent(result.getCompletedEventId(), result.getSuspendedAt(),
                input.eventId(), input.refundId(), license.getLicenseId(), license.getOrderNo(),
                license.getBuyerSubject(), license.getProductId(), license.getProductCode(), license.getPaymentId());
        outbox.record("License", license.getLicenseId(), event);
        entityManager.flush();
    }
}
