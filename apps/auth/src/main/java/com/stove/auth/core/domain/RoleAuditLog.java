package com.stove.auth.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 계정 역할 변경의 주체와 내용을 남기는 감사 기록.
 */
@Entity
@Table(name = "role_audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleAuditLog {
    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 계정을 식별하는 인증 주체 문자열. */
    @Column(nullable = false, length = 64)
    private String subject;

    /** 업무를 수행한 사용자 또는 시스템. */
    @Column(nullable = false, length = 80)
    private String actor;

    /** 감사 기록에 남길 업무 행동명. */
    @Column(nullable = false, length = 40)
    private String action;

    /** 역할 변경 당시의 역할 집합을 기록한 문자열. */
    @Column(nullable = false, length = 200)
    private String roles;

    /** 업무 행동을 기록한 시각. */
    @Column(nullable = false)
    private Instant occurredAt;

    private RoleAuditLog(String subject, String actor, String action, String roles) {
        this.subject = subject;
        this.actor = actor;
        this.action = action;
        this.roles = roles;
        this.occurredAt = Instant.now();
    }

    public static RoleAuditLog assigned(UserAccount account, String actor) {
        return new RoleAuditLog(account.getSubject(), actor, "ROLE_ASSIGNED", currentRoles(account));
    }

    /** 운영자가 역할을 부여하거나 회수한 변경을 기록한다. 기록에는 변경 후 역할 집합을 남긴다. */
    public static RoleAuditLog roleChanged(UserAccount account, String actor) {
        return new RoleAuditLog(account.getSubject(), actor, "ROLE_CHANGED", currentRoles(account));
    }

    /** 운영자가 계정 상태를 바꾼 변경을 기록한다. 기록에는 변경 후 상태를 남긴다. */
    public static RoleAuditLog statusChanged(UserAccount account, String actor) {
        return new RoleAuditLog(account.getSubject(), actor, "STATUS_CHANGED", account.getStatus().name());
    }

    private static String currentRoles(UserAccount account) {
        return account.getRoles().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
