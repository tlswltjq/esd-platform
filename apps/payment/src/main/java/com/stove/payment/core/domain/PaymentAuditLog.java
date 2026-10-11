package com.stove.payment.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 결제 및 환불 행동의 주체와 내용을 남기는 감사 기록.
 */
@Entity
@Table(name = "payment_audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAuditLog {
    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문·결제·이용권·정산을 연결하는 주문번호. */
    @Column(nullable = false, length = 40)
    private String orderNo;

    /** 업무를 수행한 사용자 또는 시스템. */
    @Column(nullable = false, length = 80)
    private String actor;

    /** 감사 기록에 남길 업무 행동명. */
    @Column(nullable = false, length = 40)
    private String action;

    /** 업무 행동의 상세 내용. */
    @Column(nullable = false, length = 200)
    private String detail;

    /** 업무 행동을 기록한 시각. */
    @Column(nullable = false)
    private Instant occurredAt;

    private PaymentAuditLog(String orderNo, String actor, String action, String detail) {
        this.orderNo = orderNo;
        this.actor = actor;
        this.action = action;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public static PaymentAuditLog of(String orderNo, String actor, String action, String detail) {
        return new PaymentAuditLog(orderNo, actor, action, detail);
    }
}
