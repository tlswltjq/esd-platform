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

@Entity
@Table(name = "payment_audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String orderNo;

    @Column(nullable = false, length = 80)
    private String actor;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(nullable = false, length = 200)
    private String detail;

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
