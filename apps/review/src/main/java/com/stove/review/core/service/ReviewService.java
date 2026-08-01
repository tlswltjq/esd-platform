package com.stove.review.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.event.payload.ReviewRejectedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.review.core.domain.ReviewProperties;
import com.stove.review.core.domain.ReviewRequest;
import com.stove.review.core.domain.ReviewRequestRepository;
import com.stove.review.core.domain.ReviewStatus;
import com.stove.review.core.port.RatingBoardClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등급분류 심의 워크플로우.
 * 상품 등록 파이프라인이 이 서비스의 상태머신에 물려 있어, 승인 이벤트 없이는 상품이 생성되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {

    private static final String AGGREGATE = "ReviewRequest";
    private static final String CONSUMER_GROUP = "review";

    private final ReviewRequestRepository reviewRepository;
    private final RatingBoardClient ratingBoardClient;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;
    private final ReviewProperties properties;

    /**
     * [등록] studio → GameRegistered → review (접수)
     *
     * <p>중복 수신 마킹을 접수 트랜잭션 안에서 함께 처리한다.
     */
    public void receive(String eventId, String eventType, GameRegisteredEvent event) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }

        ReviewRequest request = reviewRepository.findByProductCode(event.productCode())
                .map(existing -> {
                    existing.reopen(event.title(), event.price()); // 반려 후 재신청
                    return existing;
                })
                .orElseGet(() -> reviewRepository.save(ReviewRequest.received(
                        event.gameId(), event.productCode(), event.title(), event.sellerId(),
                        event.price(), event.currency(), event.selfRated())));

        if (request.isSelfRated()) {
            // 자체등급분류: 게임위 접수 없이 내부 심사로 진행
            request.startReview(null);
            if (properties.autoApproveSelfRated()) {
                approveInternal(request, properties.defaultSelfRatingCode());
            }
        } else {
            request.startReview(ratingBoardClient.submit(
                    request.getProductCode(), request.getTitle(), request.getSellerId()));
        }
        log.info("심의 접수 productCode={} selfRated={} status={}",
                request.getProductCode(), request.isSelfRated(), request.getStatus());
    }

    public void approve(Long reviewId, String ratingCode) {
        approveInternal(findRequest(reviewId), ratingCode);
    }

    public void reject(Long reviewId, String reasonCode, String reason) {
        ReviewRequest request = findRequest(reviewId);
        request.reject(reasonCode, reason);

        outboxRecorder.record(AGGREGATE, request.getProductCode(),
                ReviewRejectedEvent.of(request.getGameId(), request.getProductCode(), reasonCode, reason));

        log.info("심의 반려 productCode={} reason={}", request.getProductCode(), reason);
    }

    private void approveInternal(ReviewRequest request, String ratingCode) {
        request.approve(ratingCode);

        // [승인] review → ReviewApproved → catalog(상품 생성) + studio(상태 반영)
        outboxRecorder.record(AGGREGATE, request.getProductCode(),
                ReviewApprovedEvent.of(request.getGameId(), request.getProductCode(), request.getTitle(),
                        request.getSellerId(), request.getPrice(), request.getCurrency(),
                        ratingCode, request.isSelfRated()));

        log.info("심의 승인 productCode={} rating={}", request.getProductCode(), ratingCode);
    }

    @Transactional(readOnly = true)
    public List<ReviewRequest> getRequests(ReviewStatus status) {
        return status == null ? reviewRepository.findAll() : reviewRepository.findByStatusOrderByIdAsc(status);
    }

    private ReviewRequest findRequest(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "reviewId=" + reviewId));
    }
}
