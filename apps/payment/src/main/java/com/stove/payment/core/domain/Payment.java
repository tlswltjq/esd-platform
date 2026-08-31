package com.stove.payment.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 결제 애그리거트. 금액 검증 규칙이 전부 여기 있다 — 게이트 배치는 docs/code-notes.md */
@Entity
@Getter
@Table(name = "payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문번호 = 결제의 자연 키. 유니크 제약으로 주문당 결제 1건을 보장한다. */
    @Column(nullable = false, unique = true, length = 40)
    private String orderNo;

    @Column(nullable = false)
    private Long memberId;

    /** 사전등록 금액(= 주문 확정 금액). 승인 금액 대조의 기준값. */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 30)
    private String method;

    /** 결제창을 연 시각. createdAt 과 합칠 수 없다 — docs/code-notes.md */
    private Instant preparedAt;

    @Column(length = 100)
    private String pgTxId;

    /** 콜백 멱등 키. <b>전역 유니크를 걸면 안 된다</b> — PG 가 만드는 값이다. [D-008] */
    @Column(length = 100)
    private String idempotencyKey;

    @Convert(converter = OrderLinesConverter.class)
    @Column(name = "lines_json", columnDefinition = "json")
    private List<OrderLine> lines;

    private Instant paidAt;

    private Instant canceledAt;

    @Column(length = 200)
    private String cancelReason;

    /** 재개 시도 횟수. 지표가 아니라 행에 남겨야 하는 이유는 docs/code-notes.md */
    @Column(nullable = false)
    private int cancelAttempts;

    /** 다음 재개를 시도해도 되는 시각. {@code null} 이면 아직 예약된 적이 없다. */
    private Instant nextCancelAttemptAt;

    /** CANCELING 진입 시각. updatedAt 으로 대신할 수 없다 — 예산 판정의 기준. */
    private Instant cancelingSince;

    private Instant failedAt;

    /** PG 가 준 거절 코드. 사유별 집계의 기준이라 사람이 읽는 문구와 따로 둔다. */
    @Column(length = 50)
    private String failReasonCode;

    @Column(length = 200)
    private String failReason;

    private Payment(String orderNo, Long memberId, long amount, String currency, List<OrderLine> lines) {
        this.orderNo = orderNo;
        this.memberId = memberId;
        this.amount = amount;
        this.currency = currency;
        this.lines = lines;
        this.status = PaymentStatus.READY;
    }

    public static Payment ready(String orderNo, Long memberId, long amount, String currency, List<OrderLine> lines) {
        return new Payment(orderNo, memberId, amount, currency, lines);
    }

    /** 결제 가능 시간이 지났는가. 만료가 없으면 옛 가격이 영원히 유효하다. [D-029] */
    public void requireWithinWindow(Duration window) {
        Instant createdAt = getCreatedAt();
        if (createdAt != null && createdAt.plus(window).isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.PAYMENT_WINDOW_EXPIRED,
                    "주문 생성 %s 경과: orderNo=%s".formatted(window, orderNo));
        }
    }

    /** 게이트 2: PG 사전등록. 만료를 여기서 막는 이유는 docs/code-notes.md */
    public void prepare(String pgTxId, String method, Duration window) {
        requireWithinWindow(window);
        if (status != PaymentStatus.READY && status != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED, "결제 준비 불가 상태: " + status);
        }
        this.pgTxId = pgTxId;
        this.method = method;
        this.preparedAt = Instant.now();
        this.status = PaymentStatus.PENDING;
    }

    /**
     * 결제창이 너무 오래 열려 있었는가. <b>참이어도 승인을 거절하지 않는다.</b>
     * {@code preparedAt} 이 없으면 만료가 아니라고 답한다 — 모를 때는 돈을 움직이지 않는다.
     * 근거는 docs/code-notes.md
     */
    public boolean checkoutExpired(Duration window) {
        return preparedAt != null && preparedAt.plus(window).isBefore(Instant.now());
    }

    /**
     * 게이트 3+4: 승인 확정.
     * @return 이미 승인된 건이면 false(중복 콜백) — 호출측은 이벤트를 재발행하지 않는다.
     */
    public boolean approve(String pgTxId, long paidAmount, String idempotencyKey) {
        if (status == PaymentStatus.PAID) {
            if (Objects.equals(this.idempotencyKey, idempotencyKey)) {
                return false;   // 같은 콜백의 재전송
            }
            // 다른 승인이 또 왔다 — 연동 오류이거나 위·변조. 조용히 무시하지 않는다.
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "이미 다른 승인으로 확정된 결제: orderNo=%s".formatted(orderNo));
        }
        if (status != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED, "승인 불가 상태: " + status);
        }
        if (paidAmount != this.amount) {
            // 위·변조 또는 PG 연동 오류. 승인 확정하지 않고 운영 알람 대상으로 남긴다.
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "요청=%d, 승인=%d, orderNo=%s".formatted(this.amount, paidAmount, this.orderNo));
        }
        this.pgTxId = pgTxId;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PAID;
        this.paidAt = Instant.now();
        return true;
    }

    /**
     * 취소 1단계: PG 환불을 요청하겠다는 의도를 기록한다. docs/code-notes.md
     *
     * @return 이미 취소가 끝난 건이면 false
     */
    public boolean beginCancel(String reason) {
        if (status == PaymentStatus.CANCELED) {
            return false;
        }
        if (status != PaymentStatus.PAID && status != PaymentStatus.CANCELING) {
            throw new BusinessException(ErrorCode.CONFLICT, "취소 불가 상태: " + status);
        }
        // 처음 진입에서만 잡는다 — 조건 없이 덮으면 예산이 영원히 안 찬다.
        if (status != PaymentStatus.CANCELING) {
            this.cancelingSince = Instant.now();
        }
        this.status = PaymentStatus.CANCELING;
        this.cancelReason = reason;
        return true;
    }

    /** 다음 재개 시도를 예약한다. 시도 횟수도 함께 올린다. */
    public void scheduleCancelRetry(Duration backoff) {
        this.cancelAttempts++;
        this.nextCancelAttemptAt = Instant.now().plus(backoff);
    }

    /** 착수 직후의 첫 유예. <b>시도 횟수를 올리지 않는다</b> — 아직 시도한 적이 없다. */
    public void scheduleFirstCancelRetry(Duration initialDelay) {
        this.nextCancelAttemptAt = Instant.now().plus(initialDelay);
    }

    /** 예산을 넘겼는가. <b>포기 신호가 아니라 사람을 부르는 신호다.</b> docs/code-notes.md */
    public boolean cancelBudgetExceeded(Duration budget, Instant now) {
        return status == PaymentStatus.CANCELING
                && cancelingSince != null
                && cancelingSince.plus(budget).isBefore(now);
    }

    /** 취소 2단계: PG 환불이 실제로 끝난 뒤 확정한다. */
    public void completeCancel() {
        if (status == PaymentStatus.CANCELED) {
            return;
        }
        if (status != PaymentStatus.CANCELING) {
            throw new BusinessException(ErrorCode.CONFLICT, "취소 확정 불가 상태: " + status);
        }
        this.status = PaymentStatus.CANCELED;
        this.canceledAt = Instant.now();
        // 확정된 행은 더 이상 스윕 대상이 아니다. 예약을 남겨 두면 인덱스에 죽은 값이 쌓인다.
        this.nextCancelAttemptAt = null;
    }

    /** 취소 절차를 밟을 수 있는가. 보상 경로가 예외 대신 값으로 판단해야 한다. */
    public boolean cancelable() {
        return status == PaymentStatus.PAID
                || status == PaymentStatus.CANCELING
                || status == PaymentStatus.CANCELED;
    }

    /**
     * PG 승인 거절로 결제를 종료한다. {@code FAILED} 는 <b>종단 상태</b>다. docs/code-notes.md
     *
     * @return 이미 실패로 끝난 건이면 false(거절 콜백 재전송) — 호출측은 이벤트를 재발행하지 않는다
     */
    public boolean fail(String pgTxId, String reasonCode, String reason) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.FAILED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "승인 거절 불가 상태: " + status);
        }
        if (!Objects.equals(this.pgTxId, pgTxId)) {
            throw new BusinessException(ErrorCode.PAYMENT_TX_MISMATCH,
                    "사전등록=%s, 콜백=%s, orderNo=%s".formatted(this.pgTxId, pgTxId, this.orderNo));
        }
        if (status == PaymentStatus.FAILED) {
            return false;   // 같은 거절의 재전송
        }
        this.status = PaymentStatus.FAILED;
        this.failedAt = Instant.now();
        this.failReasonCode = reasonCode;
        this.failReason = reason;
        return true;
    }
}
