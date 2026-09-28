package com.stove.auth.core.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleAuditLogRepository extends JpaRepository<RoleAuditLog, Long> {
}
