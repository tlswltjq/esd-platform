package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.AuditLog;
import com.stove.catalog.core.domain.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository repository;

    public void record(String actor, String action, Object targetId, String details) {
        repository.save(AuditLog.of(actor, action, targetId, details));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(String actor, Object targetId, String details) {
        repository.save(AuditLog.of(actor, "PROMOTION_DENIED", targetId, details));
    }
}
