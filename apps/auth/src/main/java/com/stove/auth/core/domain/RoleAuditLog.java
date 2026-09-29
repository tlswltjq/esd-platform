package com.stove.auth.core.domain;

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
@Table(name = "role_audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String subject;

    @Column(nullable = false, length = 80)
    private String actor;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(nullable = false, length = 200)
    private String roles;

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
        String roles = account.getRoles().stream().map(Enum::name).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return new RoleAuditLog(account.getSubject(), actor, "ROLE_ASSIGNED", roles);
    }
}
