package com.stove.studio.core.service;

import com.stove.studio.core.domain.Workspace;
import com.stove.studio.core.domain.WorkspaceRepository;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository repository;
    private final ProcessedEventGuard processedEventGuard;

    public static final String CONSUMER_GROUP = "studio-workspace";

    @Transactional
    public Workspace getOrCreatePersonal(String ownerSubject) {
        return repository.findByOwnerSubject(ownerSubject).orElseGet(() -> create(ownerSubject));
    }

    @Transactional
    public void receiveRegistration(String eventId, String eventType, String ownerSubject) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        getOrCreatePersonal(ownerSubject);
    }

    private Workspace create(String ownerSubject) {
        try {
            return repository.saveAndFlush(Workspace.personal(ownerSubject));
        } catch (DataIntegrityViolationException concurrentCreation) {
            return repository.findByOwnerSubject(ownerSubject).orElseThrow(() -> concurrentCreation);
        }
    }
}
