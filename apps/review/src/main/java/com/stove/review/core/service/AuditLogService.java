package com.stove.review.core.service;

import com.stove.review.core.domain.AuditLog;
import com.stove.review.core.domain.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository repository;

    public void record(String actor, String action, Object targetId, String details) {
        repository.save(AuditLog.of(actor, action, targetId, details));
    }
}
