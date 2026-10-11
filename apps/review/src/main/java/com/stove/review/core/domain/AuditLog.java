package com.stove.review.core.domain;

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
 * 업무 행동의 주체·대상·내용을 남기는 서비스별 감사 기록.
 */
@Entity
@Table(name = "audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** 업무를 수행한 사용자 또는 시스템. */
    @Column(nullable = false, length = 100) private String actor;
    /** 감사 기록에 남길 업무 행동명. */
    @Column(nullable = false, length = 60) private String action;
    /** 감사 기록이 가리키는 업무 대상 종류. */
    @Column(nullable = false, length = 50) private String targetType;
    /** 감사 기록이 가리키는 업무 대상 식별자. */
    @Column(nullable = false, length = 100) private String targetId;
    /** 감사 또는 상태 변경의 상세 내용. */
    @Column(length = 1000) private String details;
    /** 업무 행동을 기록한 시각. */
    @Column(nullable = false, updatable = false) private Instant occurredAt;

    public static AuditLog of(String actor, String action, Object targetId, String details) {
        AuditLog log = new AuditLog();
        log.actor = actor;
        log.action = action;
        log.targetType = "ReviewCase";
        log.targetId = String.valueOf(targetId);
        log.details = details;
        log.occurredAt = Instant.now();
        return log;
    }
}
