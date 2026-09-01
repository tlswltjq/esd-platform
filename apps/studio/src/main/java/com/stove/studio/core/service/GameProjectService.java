package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.GameProjectRepository;
import com.stove.studio.core.domain.NewProject;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게임 프로젝트 — 등록부터 심의 결과 반영까지의 상태머신.
 *
 * <pre>
 * DRAFT ──submit──▶ SUBMITTED ──ReviewApproved──▶ APPROVED
 *                             └─ReviewRejected──▶ REJECTED ──submit──▶ SUBMITTED
 * </pre>
 *
 * <p>전이 네 경로가 전부 이 애그리거트 하나를 만지므로 한 클래스에 둔다. docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GameProjectService {

    /** Outbox 애그리거트 이름. 빌드 등록도 이 스트림이다 — 한 상품의 사건은 한 줄로 늘어선다. */
    static final String AGGREGATE = "GameProject";

    /** Kafka 컨슈머 그룹이자 Inbox 멱등 키. 리스너도 이 상수를 참조한다 — {@code ConsumerGroupRules} 참고. */
    public static final String CONSUMER_GROUP = "studio";

    private final GameProjectRepository projectRepository;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;

    public GameProject create(NewProject request) {
        projectRepository.findByProductCode(request.productCode()).ifPresent(p -> {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 존재하는 상품코드입니다.");
        });
        return projectRepository.save(GameProject.create(request.productCode(), request.title(),
                request.sellerId(), request.price(), request.currency(), request.selfRated()));
    }

    /** [등록] studio → GameRegistered → review */
    public void submitForReview(Long gameId, Long sellerId) {
        GameProject project = requireOwned(gameId, sellerId);
        project.submit();

        outboxRecorder.record(AGGREGATE, project.getProductCode(),
                GameRegisteredEvent.of(project.getId(), project.getProductCode(), project.getTitle(),
                        project.getSellerId(), project.getPrice(), project.getCurrency(), project.isSelfRated()));

        log.info("심의 신청 gameId={} productCode={} selfRated={}",
                gameId, project.getProductCode(), project.isSelfRated());
    }

    public void applyApproval(String eventId, String eventType, String productCode, String ratingCode) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        GameProject project = requireByProductCode(productCode);
        if (!project.approve(ratingCode)) {
            log.warn("심의 신청 상태가 아닌 프로젝트의 승인 이벤트 — 무시 productCode={} status={}",
                    productCode, project.getStatus());
            return;
        }
        log.info("심의 승인 반영 productCode={} rating={}", productCode, ratingCode);
    }

    public void applyRejection(String eventId, String eventType, String productCode, String reason) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        GameProject project = requireByProductCode(productCode);
        if (!project.reject(reason)) {
            log.warn("심의 신청 상태가 아닌 프로젝트의 반려 이벤트 — 무시 productCode={} status={}",
                    productCode, project.getStatus());
            return;
        }
        log.info("심의 반려 반영 productCode={} reason={}", productCode, reason);
    }

    @Transactional(readOnly = true)
    public List<GameProject> findBySeller(Long sellerId) {
        return projectRepository.findBySellerIdOrderByIdDesc(sellerId);
    }

    /**
     * 소유자 확인까지 끝난 프로젝트.
     *
     * <p>빌드 등록도 같은 확인을 거쳐야 해서 밖으로 연다. 트랜잭션을 새로 열지 않고
     * <b>부르는 쪽의 트랜잭션에 참여</b>하므로, 빌드 저장과 소유 확인이 한 경계 안에 남는다.
     * 프로젝트 조회를 빌드 쪽에서 리포지토리로 직접 하면 같은 애그리거트를 만지는 클래스가 둘이 된다.
     */
    public GameProject requireOwned(Long gameId, Long sellerId) {
        GameProject project = projectRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "gameId=" + gameId));
        project.requireOwner(sellerId);
        return project;
    }

    private GameProject requireByProductCode(String productCode) {
        return projectRepository.findByProductCode(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "productCode=" + productCode));
    }
}
