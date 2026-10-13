package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.PaymentErrorCode;
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

    /**
     * 개발용 PG 거래의 처리 상태.
     */
    public enum Status {
        /** 시뮬레이터 거래를 사전등록한 상태. */
        PREPARED,
        /** 시뮬레이터 거래의 결제가 승인된 상태. */
        APPROVED,
        /** 시뮬레이터 결제가 거절된 상태. */
        DECLINED,
        /** 시뮬레이터 결제의 응답 시간을 초과한 상태. */
        TIMED_OUT,
        /** 취소 처리가 완료된 상태. */
        CANCELED
    }

    /** PG에 사전등록한 거래 식별자. */
    @Id
    @Column(length = 100)
    private String pgTxId;

    /** 주문·결제·이용권·정산을 연결하는 주문번호. */
    @Column(nullable = false, length = 40)
    private String orderNo;

    /** 결제 또는 환불 대상 금액(최소 화폐 단위). */
    @Column(nullable = false)
    private long amount;

    /** 금액의 통화 코드. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** 개발용 PG 거래의 사전등록·승인·거절·취소 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** 처리 결과 또는 요청 사유를 구분하는 코드. */
    @Column(length = 50)
    private String reasonCode;

    /** 처리 결과 또는 요청 사유에 대한 설명. */
    @Column(length = 200)
    private String reason;

    /** 기록이 생성된 시각. */
    @Column(nullable = false)
    private Instant createdAt;

    /** 동시 거래 수정 충돌을 감지하는 낙관적 잠금 버전. */
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
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (status == Status.CANCELED) return;
        // 기존 서명 콜백도 데모 PG의 승인 사실로 취급하므로 PREPARED 취소를 허용한다.
        if (status != Status.APPROVED && status != Status.PREPARED) {
            throw new BusinessException(ErrorCode.CONFLICT, "취소할 수 없는 시뮬레이터 거래: " + status);
        }
        status = Status.CANCELED;
    }
}
