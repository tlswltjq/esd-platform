package com.stove.catalog.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.domain.CatalogPublicationJob;
import com.stove.catalog.core.domain.CatalogPublicationJobRepository;
import com.stove.catalog.core.domain.ReleaseSubmission;
import com.stove.catalog.core.port.PublicationTracing;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** Each job is atomic; failure bookkeeping commits after the failed publication rolled back. */
@Slf4j
@RequiredArgsConstructor
public class PublishQueuedReleases {
    private final CatalogPublicationJobRepository jobs;
    private final PublishProjectRelease publish;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final PublicationTracing tracing;

    public int execute() {
        List<CatalogPublicationJob> due = jobs.findByStateAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscRequestIdAsc(
                "PENDING", Instant.now(), PageRequest.of(0, batchSize));
        int attempted = 0;
        for (CatalogPublicationJob candidate : due) {
            try {
                Boolean processed = transaction.execute(status -> {
                    CatalogPublicationJob job = jobs.lock(candidate.getRequestId()).orElse(null);
                    if (job == null || !job.due(Instant.now())) return false;
                    return tracing.call(job.getTraceParent(), job.getRequestId().toString(), () -> {
                        publish.execute(submission(job));
                        job.complete();
                        return true;
                    });
                });
                if (Boolean.TRUE.equals(processed)) attempted++;
            } catch (RuntimeException failure) {
                transaction.executeWithoutResult(status -> jobs.lock(candidate.getRequestId()).ifPresent(job ->
                        job.failed(failure.getClass().getSimpleName() + ": " + failure.getMessage(), Instant.now())));
                log.warn("Catalog publication job {} failed and remains scheduled", candidate.getRequestId());
                attempted++;
            }
        }
        return attempted;
    }

    private ReleaseSubmission submission(CatalogPublicationJob job) {
        try {
            return new ReleaseSubmission(job.getSourceEventId(), "ProjectReleaseRequested", job.getOccurredAt(),
                    job.getRequestId(), job.getReviewRequestId(), mapper.readValue(job.getSnapshot(), ProjectSnapshot.class));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("출시 작업의 원본을 읽을 수 없습니다.", failure);
        }
    }
}
