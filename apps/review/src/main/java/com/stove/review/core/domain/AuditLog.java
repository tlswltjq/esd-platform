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

@Entity
@Table(name = "audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 100) private String actor;
    @Column(nullable = false, length = 60) private String action;
    @Column(nullable = false, length = 50) private String targetType;
    @Column(nullable = false, length = 100) private String targetId;
    @Column(length = 1000) private String details;
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
