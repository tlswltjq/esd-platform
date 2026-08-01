package com.stove.payment.domain;

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
 *   <li>멱등키: 같은 콜백이 여러 번 와도 승인은 한 번 (여기 + 유니크 제약)</li>
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

    /** 콜백 멱등 키. 유니크 제약이 중복 승인의 마지막 방어선. */
    @Column(length = 100, unique = true)
    private String idempotencyKey;

    @Convert(converter = OrderLinesConverter.class)
    @Column(name = "lines_json", columnDefinition = "json")
    private List<OrderLine> lines;

    private Instant paidAt;

    private Instant canceledAt;

    @Column(length = 200)
    private String cancelReason;

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
            return false;
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

    /** @return 이미 취소된 건이면 false */
    public boolean cancel(String reason) {
        if (status == PaymentStatus.CANCELED) {
            return false;
        }
        if (status != PaymentStatus.PAID) {
            throw new BusinessException(ErrorCode.CONFLICT, "취소 불가 상태: " + status);
        }
        this.status = PaymentStatus.CANCELED;
        this.canceledAt = Instant.now();
        this.cancelReason = reason;
        return true;
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
    }
}
