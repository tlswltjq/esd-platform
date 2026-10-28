package com.stove.license.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.ApprovalCorrectionRequest;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseSuspension;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.LicenseApprovalCorrection;
import com.stove.license.core.domain.LicenseApprovalCorrectionRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor @Transactional(isolation=Isolation.READ_COMMITTED)
public class ApplyApprovalCorrection {
    public static final String CONSUMER_GROUP="license-approval-correction";
    private final LicenseRepository licenses;
    private final LicenseSuspensionRepository suspensions;
    private final LicenseApprovalCorrectionRepository corrections;
    private final ProcessedEventGuard inbox;
    private final OutboxRecorder outbox;
    private final EntityManager entityManager;
    private final Clock clock;
    public void execute(ApprovalCorrectionRequest input) {
        if (input==null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        License license=licenses.lockByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!license.getLicenseId().equals(input.licenseId()) || !license.getBuyerSubject().equals(input.buyerSubject())
                || !license.getProductId().equals(input.productId()) || !license.getProductCode().equals(input.productCode())
                || !license.getPaymentId().equals(input.paymentId())) throw new BusinessException(ErrorCode.CONFLICT);
        LicenseApprovalCorrection known=corrections.findById(input.eventId()).orElse(null);
        if (known!=null) {
            known.verify(input);
            if (inbox.firstDelivery(input.eventId(),CONSUMER_GROUP,EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED))
                throw new BusinessException(ErrorCode.CONFLICT,"복구 Inbox가 없습니다.");
            return;
        }
        LicenseSuspension suspension=suspensions.findByRequestEventId(input.suspensionRequestEventId()).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        if (!suspension.getLicenseId().equals(input.licenseId()) || !suspension.getRefundId().equals(input.refundId())
                || !suspension.getCompletedEventId().equals(input.suspensionEventId()) || input.occurredAt().isBefore(suspension.getSuspendedAt()))
            throw new BusinessException(ErrorCode.CONFLICT,"승인 정정 대상의 실제 정지 근거가 다릅니다.");
        if (!inbox.firstDelivery(input.eventId(),CONSUMER_GROUP,EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED)) throw new BusinessException(ErrorCode.CONFLICT);
        license.restore(input.suspensionRequestEventId());
        LicenseApprovalCorrection result=LicenseApprovalCorrection.record(license,input,clock.instant()); corrections.save(result);
        outbox.record("License",license.getLicenseId(),new LicenseApprovalCorrectedEvent(result.getCompletedEventId(),result.getRestoredAt(),
                input.eventId(),input.refundId(),input.reviewId(),input.stopRequestId(),input.licenseId(),input.orderNo(),input.buyerSubject(),
                input.productId(),input.productCode(),input.paymentId(),input.suspensionRequestEventId(),input.suspensionEventId()));
        entityManager.flush();
    }
}
