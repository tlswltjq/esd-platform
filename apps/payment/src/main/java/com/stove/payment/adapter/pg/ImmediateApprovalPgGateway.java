package com.stove.payment.adapter.pg;

import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.port.PgGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 외부 호출 없이 모든 결제를 승인으로 답한다(결정 in-process-pg-approval).
 * PG 어댑터가 없는 동안 구매→지급→다운로드를 잇는 자리이며, 실제 PG를 되살릴 때 지운다.
 */
public class ImmediateApprovalPgGateway implements PgGateway {
    private static final String PROVIDER = "SIMULATED";
    private static final String TRANSACTION_PREFIX = "in-process-";

    private final Clock clock;

    public ImmediateApprovalPgGateway(Clock clock) { this.clock = clock; }

    /** 거래 번호는 paymentId에서 만들어 같은 결제의 재시도가 같은 번호를 받는다. PG 비용은 알 수 없으므로 비운다. */
    @Override public PgResult charge(PgChargeRequest request) {
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return new PgResult(PROVIDER, request.paymentId(), request.orderNo(), request.amount(), request.currency(),
                TRANSACTION_PREFIX + request.paymentId(), occurredAt, PgResult.Decision.APPROVED, null, null, null);
    }
}
