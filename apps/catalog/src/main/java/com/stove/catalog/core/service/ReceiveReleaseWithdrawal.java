package com.stove.catalog.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.domain.CatalogRelease;
import com.stove.catalog.core.domain.CatalogReleaseRepository;
import com.stove.catalog.core.domain.CatalogPublicationJob;
import com.stove.catalog.core.domain.CatalogPublicationJobRepository;
import com.stove.catalog.core.domain.CatalogRequestGateRepository;
import com.stove.catalog.core.domain.ReleaseWithdrawal;
import com.stove.catalog.core.domain.ReleaseWithdrawalInput;
import com.stove.catalog.core.domain.ReleaseWithdrawalRepository;
import com.stove.catalog.core.domain.ReleaseSubmission;
import com.stove.common.kafka.contract.payload.ProjectReleaseWithdrawalResolvedEvent;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReceiveReleaseWithdrawal {
    public static final String CONSUMER_GROUP = PublishProjectRelease.CONSUMER_GROUP;
    private final CatalogRequestGateRepository gates;
    private final ReleaseWithdrawalRepository withdrawals;
    private final CatalogReleaseRepository releases;
    private final ProcessedEventGuard guard;
    private final OutboxRecorder outbox;
    private final ObjectMapper mapper;
    private final CatalogPublicationJobRepository jobs;
    private final EntityManager entityManager;

    public void execute(ReleaseWithdrawalInput input) {
        Long requestId = input.request().requestId();
        gates.ensure(requestId);
        gates.lock(requestId).orElseThrow();
        ReleaseWithdrawal withdrawal = withdrawals.findById(input.request().withdrawalId()).orElse(null);
        if (withdrawal != null) {
            withdrawal.requireSame(input.request());
            if (withdrawal.isResolved()) return;
        }
        boolean first = guard.firstDelivery(input.eventId(), CONSUMER_GROUP, input.eventType());
        if (withdrawal == null) {
            if (!first) throw new BusinessException(ErrorCode.CONFLICT, "철회 명령 근거가 없습니다.");
            withdrawal = withdrawals.save(ReleaseWithdrawal.receive(input));
        }
        resolve(withdrawal);
    }

    public void resume(Long requestId) {
        gates.ensure(requestId);
        gates.lock(requestId).orElseThrow();
        for (ReleaseWithdrawal withdrawal : withdrawals.findByRequestIdAndResolvedFalseOrderByWithdrawalIdAsc(requestId))
            resolve(withdrawal);
    }

    private void resolve(ReleaseWithdrawal withdrawal) {
        CatalogRelease release = releases.findById(withdrawal.getRequestId()).orElse(null);
        ProjectSnapshot snapshot;
        if (release == null) {
            CatalogPublicationJob job = jobs.findById(withdrawal.getRequestId()).orElse(null);
            if (job == null) return;
            snapshot = snapshot(job.getSnapshot());
            ReleaseSubmission submission = new ReleaseSubmission(job.getSourceEventId(), "ProjectReleaseRequested",
                    job.getOccurredAt(), job.getRequestId(), job.getReviewRequestId(), snapshot);
            release = CatalogRelease.withdrawn(submission, job.getSnapshot(), Instant.now().truncatedTo(ChronoUnit.MICROS),
                    withdrawal.getOwnerSubject(), withdrawal.getReason());
            ProjectWithdrawalResult result = withdrawal.resolve(release, snapshot);
            entityManager.persist(release);
            outbox.record("CatalogRelease", release.getProductCode(), ProjectReleaseWithdrawalResolvedEvent.of(result));
        } else {
            snapshot = snapshot(release.getSnapshot());
            outbox.record("CatalogRelease", release.getProductCode(),
                    ProjectReleaseWithdrawalResolvedEvent.of(withdrawal.resolve(release, snapshot)));
        }
    }

    private ProjectSnapshot snapshot(String json) {
        try {
            return mapper.readValue(json, ProjectSnapshot.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("출시 원본을 읽을 수 없습니다.", failure);
        }
    }
}
