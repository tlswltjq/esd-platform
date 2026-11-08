package com.stove.order.core.service;

import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.annotation.Transactional;

/** 결정 125: 지급 확인(LicenseGranted)을 경계보다 오래 기다리는 환불 수. */
@Transactional(readOnly = true)
public class ReadLicenseGrantDelays {
    private final RefundRepository refunds;
    private final Clock clock;
    private final Duration threshold;

    public ReadLicenseGrantDelays(RefundRepository refunds, Clock clock, Duration threshold) {
        this.refunds = refunds; this.clock = clock; this.threshold = LicenseGrantDelay.require(threshold);
    }

    public long count() {
        return refunds.countByStatusAndLicenseGrantWaitStartedAtLessThanEqual(RefundStatus.PENDING_LICENSE_GRANT,
                clock.instant().truncatedTo(ChronoUnit.MICROS).minus(threshold));
    }
}
