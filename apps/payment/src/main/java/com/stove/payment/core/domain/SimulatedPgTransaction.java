package com.stove.payment.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.domain.PgDecline;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 데모 PG가 실제로 보유한 사전등록·결과 기록. 결제 확정은 이 기록을 읽어 시작한다. */
@Entity
@Getter
@Table(name = "simulated_pg_transaction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulatedPgTransaction {

    public enum Status { PREPARED, APPROVED, DECLINED, TIMED_OUT, CANCELED }

    @Id
    @Column(length = 100)
    private String pgTxId;

    @Column(nullable = false, length = 40)
    private String orderNo;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 50)
    private String reasonCode;

    @Column(length = 200)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    private SimulatedPgTransaction(String pgTxId, String orderNo, long amount, String currency) {
        this.pgTxId = pgTxId;
        this.orderNo = orderNo;
        this.amount = amount;
        this.currency = currency;
        this.status = Status.PREPARED;
        this.createdAt = Instant.now();
    }

    public static SimulatedPgTransaction prepared(String pgTxId, String orderNo, long amount, String currency) {
        return new SimulatedPgTransaction(pgTxId, orderNo, amount, currency);
    }

    public PgApproval approve() {
        if (status != Status.PREPARED && status != Status.APPROVED) {
            throw new BusinessException(ErrorCode.CONFLICT, "승인할 수 없는 시뮬레이터 거래: " + status);
        }
        status = Status.APPROVED;
        return new PgApproval(orderNo, pgTxId, amount, "SIM-" + pgTxId);
    }

    public PgDecline decline(String code, String message, boolean timeout) {
        Status target = timeout ? Status.TIMED_OUT : Status.DECLINED;
        if (status == Status.PREPARED) {
            status = target;
            reasonCode = code;
            reason = message;
        } else if (status != target || !Objects.equals(reasonCode, code)) {
            throw new BusinessException(ErrorCode.CONFLICT, "거절할 수 없는 시뮬레이터 거래: " + status);
        }
        return new PgDecline(orderNo, pgTxId, reasonCode, reason);
    }

    public void cancel(long requestedAmount) {
        if (amount != requestedAmount) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (status == Status.CANCELED) return;
        // 기존 서명 콜백도 데모 PG의 승인 사실로 취급하므로 PREPARED 취소를 허용한다.
        if (status != Status.APPROVED && status != Status.PREPARED) {
            throw new BusinessException(ErrorCode.CONFLICT, "취소할 수 없는 시뮬레이터 거래: " + status);
        }
        status = Status.CANCELED;
    }
}
