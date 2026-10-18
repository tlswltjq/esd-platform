package com.stove.review.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.review.core.domain.ProjectReview;
import com.stove.review.core.domain.ProjectReviewRepository;
import com.stove.review.core.domain.ReviewStatus;
import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadProjectReviews {
    private final ProjectReviewRepository reviews;
    private final ObjectMapper mapper;

    public ProjectReview get(Long id) {
        return reviews.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public Page<ProjectReview> list(ReviewStatus status, Long projectId, int page, int size) {
        Specification<ProjectReview> filter =
                (root, query, cb) -> {
                    Predicate state =
                            status == null
                                    ? cb.conjunction()
                                    : cb.equal(root.get("status"), status);
                    Predicate project =
                            projectId == null
                                    ? cb.conjunction()
                                    : cb.equal(root.get("projectId"), projectId);
                    return cb.and(state, project);
                };
        return reviews.findAll(
                filter,
                PageRequest.of(
                        Math.max(0, page),
                        Math.max(1, Math.min(100, size)),
                        Sort.by(Sort.Direction.DESC, "requestedAt", "reviewRequestId")));
    }

    public ProjectSnapshot snapshot(ProjectReview review) {
        try {
            return mapper.readValue(review.getSnapshot(), ProjectSnapshot.class);
        } catch (IOException failure) {
            throw new IllegalStateException("심사 스냅샷을 읽을 수 없습니다.", failure);
        }
    }
}
