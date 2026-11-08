package com.stove.order.core.service;

import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결정 125: License 단계 이벤트를 경계보다 오래 기다리는 환불 수.
 * 지급 확인(LicenseGranted)에 더해 정지·복구·승인 중단 대기도 각 상태의 진입 시각으로 같은 경계를 쓴다.
 */
@Transactional(readOnly = true)
public class ReadLicenseGrantDelays {
    /** 게이지 status 라벨로 내는 대기 상태. */
    public static final List<RefundStatus> STATUSES = List.of(RefundStatus.PENDING_LICENSE_GRANT, RefundStatus.PENDING_LICENSE_SUSPENSION,
            RefundStatus.PENDING_LICENSE_RESTORATION, RefundStatus.PENDING_APPROVAL_STOP);
    private final RefundRepository refunds;
    private final Clock clock;
    private final Duration threshold;

    public ReadLicenseGrantDelays(RefundRepository refunds, Clock clock, Duration threshold) {
        this.refunds = refunds; this.clock = clock; this.threshold = LicenseGrantDelay.require(threshold);
    }

    /** 지급 확인 대기(결정 125 원래 범위). */
    public long count() {
        return count(RefundStatus.PENDING_LICENSE_GRANT);
    }

    public long count(RefundStatus status) {
        Instant cutoff = clock.instant().truncatedTo(ChronoUnit.MICROS).minus(threshold);
        // enum switch는 합성 클래스(ReadLicenseGrantDelays$1)를 만들어 core.service 네이밍 규칙에 걸리므로 if로 고른다.
        if (status == RefundStatus.PENDING_LICENSE_GRANT) return refunds.countByStatusAndLicenseGrantWaitStartedAtLessThanEqual(status, cutoff);
        if (status == RefundStatus.PENDING_LICENSE_SUSPENSION) return refunds.countByStatusAndLicenseSuspensionWaitStartedAtLessThanEqual(status, cutoff);
        if (status == RefundStatus.PENDING_LICENSE_RESTORATION) return refunds.countByStatusAndLicenseRestorationWaitStartedAtLessThanEqual(status, cutoff);
        if (status == RefundStatus.PENDING_APPROVAL_STOP) return refunds.countByStatusAndApprovalStopWaitStartedAtLessThanEqual(status, cutoff);
        throw new IllegalArgumentException("Not a License wait status: " + status);
    }
}
