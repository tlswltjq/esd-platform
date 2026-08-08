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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 애그리거트. 금액 검증 규칙이 전부 이 엔티티 안에 있다.
 *
 * <p>검증 게이트 배치
 * <ol>
 *   <li>주문 시점: catalog 가격으로 금액 재계산 (order)</li>
 *   <li>PG 사전등록: 승인 전에 서버가 결제 금액을 PG 에 먼저 등록</li>
 *   <li>콜백 대조: PG 가 알려준 승인 금액 == 사전등록 금액 (여기)</li>
 *   <li>멱등키: 같은 콜백이 여러 번 와도 승인은 한 번 (여기 + 행 잠금)</li>
 * </ol>
 */
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

    @Column(length = 100)
    private String pgTxId;

    /**
     * 콜백 멱등 키. PG 가 만들어 주는 값이라 <b>전역 유일성을 우리가 보장할 수 없다.</b>
     *
     * <p>그래서 역할을 "이 결제에 이미 적용된 콜백인가" 하나로 좁혔다. 전역 유니크를 걸어 두면
     * PG 가 키를 재사용했을 때 다른 주문의 결제가 조회되어 엉뚱한 건이 중복으로 처리된다(D-008).
     * 동시 중복 콜백은 결제 행을 잠그고 읽어 막는다.
     */
    @Column(length = 100)
    private String idempotencyKey;

    @Convert(converter = OrderLinesConverter.class)
    @Column(name = "lines_json", columnDefinition = "json")
    private List<OrderLine> lines;

    private Instant paidAt;

    private Instant canceledAt;

    @Column(length = 200)
    private String cancelReason;

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

    /** 게이트 2: PG 사전등록. 승인 요청 금액을 서버가 먼저 확정해 둔다. */
    public void prepare(String pgTxId, String method) {
        if (status != PaymentStatus.READY && status != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED, "결제 준비 불가 상태: " + status);
        }
        this.pgTxId = pgTxId;
        this.method = method;
        this.status = PaymentStatus.PENDING;
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
            // 같은 주문에 다른 승인이 또 왔다. PG 연동 오류이거나 위·변조다.
            // 조용히 무시하면 사고가 관측되지 않으므로 알람 대상으로 남긴다.
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
     * 취소 1단계: PG 환불을 요청하겠다는 의도를 기록한다.
     *
     * <p>{@code CANCELING} 에서 다시 불릴 수 있다 — 확정 단계가 깨져 재시도하는 경우다.
     * PG 취소는 {@code pgTxId} 기준 멱등이므로 재요청이 이중 환불이 되지 않는다
     * ({@link com.stove.payment.core.port.PgClient#cancel} 계약).
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
        this.status = PaymentStatus.CANCELING;
        this.cancelReason = reason;
        return true;
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
    }

    /**
     * 취소 절차를 밟을 수 있는 상태인가.
     *
     * <p>Saga 보상 경로가 <b>예외 대신 값으로</b> 판단하기 위해 필요하다. 보상은 이벤트로 들어오므로
     * 예외를 던지면 멱등 가드 마킹까지 롤백되어 같은 이벤트가 무한 재전송된다.
     */
    public boolean cancelable() {
        return status == PaymentStatus.PAID
                || status == PaymentStatus.CANCELING
                || status == PaymentStatus.CANCELED;
    }

    /**
     * PG 승인 거절로 결제를 종료한다.
     *
     * <p>{@code FAILED} 는 <b>종단 상태</b>다. {@link #prepare} 가 READY/PENDING 에서만 열리므로
     * 카드를 바꿔 다시 시도하려면 새 주문을 만든다 — 주문 재사용 정책은 결제 실패 경로와 분리한다.
     *
     * <p>승인과 달리 멱등키를 쓰지 않는다. 돈이 움직이지 않아 PG 가 만들 승인 거래 키가 없고,
     * 재전송은 종단 상태로 흡수하면 충분하다. 대신 {@code pgTxId} 를 대조해 <b>다른 결제의
     * 콜백이 잘못 배달된 경우</b>를 걸러낸다.
     *
     * <p>{@code PAID} 에 거절이 오면 예외다. 승인과 거절이 엇갈린 PG 연동 오류이거나 위·변조이며,
     * 조용히 무시하면 사고가 관측되지 않는다({@link #approve} 와 같은 정책).
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
