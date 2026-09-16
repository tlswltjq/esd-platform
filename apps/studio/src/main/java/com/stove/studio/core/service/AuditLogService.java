package com.stove.studio.core.service;

import com.stove.studio.core.domain.AuditLog;
import com.stove.studio.core.domain.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository repository;

    public void record(String actor, String action, String targetType, Object targetId, String details) {
        repository.save(AuditLog.of(actor, action, targetType, targetId, details));
    }
}
