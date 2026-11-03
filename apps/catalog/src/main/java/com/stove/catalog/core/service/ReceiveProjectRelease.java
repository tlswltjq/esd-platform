package com.stove.catalog.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.core.domain.CatalogPublicationJob;
import com.stove.catalog.core.domain.CatalogPublicationJobRepository;
import com.stove.catalog.core.domain.CatalogRelease;
import com.stove.catalog.core.domain.CatalogProduct;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogReleaseRepository;
import com.stove.catalog.core.domain.CatalogRequestGateRepository;
import com.stove.catalog.core.domain.ReleaseSubmission;
import com.stove.catalog.core.port.PublicationTracing;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReceiveProjectRelease {
    public static final String CONSUMER_GROUP = PublishProjectRelease.CONSUMER_GROUP;
    private final CatalogRequestGateRepository gates;
    private final CatalogPublicationJobRepository jobs;
    private final CatalogReleaseRepository releases;
    private final ProcessedEventGuard guard;
    private final ObjectMapper mapper;
    private final ReceiveReleaseWithdrawal withdrawals;
    private final PublicationTracing tracing;
    private final CatalogProductRepository products;

    public void execute(ReleaseSubmission submission) {
        gates.ensure(submission.releaseRequestId());
        gates.lock(submission.releaseRequestId()).orElseThrow();
        String snapshot = json(submission.snapshot());
        CatalogRelease completed = releases.findById(submission.releaseRequestId()).orElse(null);
        if (completed != null) {
            if (!completed.getRequestEventId().equals(submission.eventId())
                    || !completed.getReviewRequestId().equals(submission.reviewRequestId())
                    || !snapshot(completed.getSnapshot()).equals(submission.snapshot()))
                throw new BusinessException(ErrorCode.CONFLICT, "같은 출시 요청의 내용이 다릅니다.");
            return;
        }
        CatalogPublicationJob existing = jobs.findById(submission.releaseRequestId()).orElse(null);
        if (existing != null) {
            existing.requireSame(submission, snapshot(existing.getSnapshot()));
            return;
        }
        if (releases.findByRequestEventId(submission.eventId()).isPresent()
                || jobs.findBySourceEventId(submission.eventId()).isPresent())
            throw new BusinessException(ErrorCode.CONFLICT, "출시 이벤트 ID가 다른 요청에 사용됐습니다.");
        requireStableIdentity(submission);
        if (!guard.firstDelivery(submission.eventId(), CONSUMER_GROUP, submission.eventType()))
            throw new BusinessException(ErrorCode.CONFLICT, "출시 접수의 업무 근거가 없습니다.");
        jobs.saveAndFlush(CatalogPublicationJob.accept(submission, snapshot, tracing.capture()));
        withdrawals.resume(submission.releaseRequestId());
    }

    private void requireStableIdentity(ReleaseSubmission submission) {
        ProjectSnapshot incoming = submission.snapshot();
        CatalogProduct product = products.lockByProjectId(incoming.projectId()).orElse(null);
        if (product != null) product.requireIdentity(incoming);
        else if (products.findByProductCode(incoming.productCode()).isPresent())
            throw new BusinessException(ErrorCode.CONFLICT, "상품 코드가 다른 Project에 연결되어 있습니다.");
        releases.findFirstByProjectIdAndContentVersionOrderByReleaseRequestIdAsc(incoming.projectId(), incoming.contentVersion())
                .ifPresent(previous -> {
                    if (!snapshot(previous.getSnapshot()).equals(incoming))
                        throw new BusinessException(ErrorCode.CONFLICT, "같은 버전의 출시 스냅샷이 다릅니다.");
                });
        jobs.findFirstByProjectIdAndContentVersionOrderByRequestIdAsc(incoming.projectId(), incoming.contentVersion())
                .ifPresent(previous -> {
                    if (!snapshot(previous.getSnapshot()).equals(incoming))
                        throw new BusinessException(ErrorCode.CONFLICT, "같은 버전의 출시 접수 스냅샷이 다릅니다.");
                });
    }

    private String json(ProjectSnapshot snapshot) {
        try { return mapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }

    private ProjectSnapshot snapshot(String json) {
        try { return mapper.readValue(json, ProjectSnapshot.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }
}
