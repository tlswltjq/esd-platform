package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.GameProjectRepository;
import com.stove.studio.core.domain.BundleComponent;
import com.stove.studio.core.domain.BundleComponentRepository;
import com.stove.studio.core.domain.NewProject;
import com.stove.studio.core.domain.ProductKind;
import com.stove.studio.core.domain.ProductFamily;
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
 * <p>전이 네 경로가 전부 이 애그리거트 하나를 만지므로 한 클래스에 둔다.
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
    private final BundleComponentRepository bundleComponentRepository;

    public GameProject create(NewProject request) {
        projectRepository.findByProductCode(request.productCode()).ifPresent(p -> {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 존재하는 상품코드입니다.");
        });
        validateFamily(request);
        GameProject project = projectRepository.save(GameProject.create(request));
        if (request.productKind() == ProductKind.BUNDLE) {
            request.bundleGameIds().forEach(componentGameId -> bundleComponentRepository.save(
                    BundleComponent.of(project.getId(), componentGameId)));
        }
        return project;
    }

    private void validateFamily(NewProject request) {
        ProductKind kind = request.productKind();
        boolean child = kind == ProductKind.DEMO || kind == ProductKind.DLC || kind == ProductKind.EDITION;
        if (child != (request.parentGameId() != null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "DEMO/DLC/EDITION에는 BASIC 상위 상품이 필요합니다.");
        }
        if (child) {
            GameProject parent = requireOwned(request.parentGameId(), request.sellerId());
            if (parent.getProductKind() != ProductKind.BASIC) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "상위 상품은 BASIC이어야 합니다.");
            }
        }
        if (kind == ProductKind.DEMO && request.price() != 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "DEMO는 무료여야 합니다.");
        }
        boolean namedEdition = request.editionName() != null && !request.editionName().isBlank();
        if ((kind == ProductKind.EDITION) != namedEdition) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "EDITION에는 에디션 이름이 필요하며 다른 상품에는 사용할 수 없습니다.");
        }
        if (kind != ProductKind.BUNDLE && !request.bundleGameIds().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "묶음 구성 상품은 BUNDLE에만 지정할 수 있습니다.");
        }
        if (kind == ProductKind.BUNDLE) {
            List<Long> members = request.bundleGameIds();
            if (members.size() < 2 || members.stream().distinct().count() != members.size()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "BUNDLE에는 중복되지 않는 구성 상품이 두 개 이상 필요합니다.");
            }
            members.forEach(id -> {
                GameProject component = requireOwned(id, request.sellerId());
                if (component.getProductKind() == ProductKind.BUNDLE) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "BUNDLE은 다른 BUNDLE을 포함할 수 없습니다.");
                }
            });
        }
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

    @Transactional(readOnly = true)
    public ProductFamily family(Long gameId, Long sellerId) {
        GameProject product = requireOwned(gameId, sellerId);
        GameProject parent = product.getParentGameId() == null ? null
                : requireOwned(product.getParentGameId(), sellerId);
        List<GameProject> children = projectRepository.findByParentGameIdOrderById(gameId);
        List<GameProject> components = bundleComponentRepository.findByBundleGameIdOrderByComponentGameId(gameId)
                .stream().map(component -> requireOwned(component.getComponentGameId(), sellerId)).toList();
        List<GameProject> bundles = bundleComponentRepository.findByComponentGameIdOrderByBundleGameId(gameId)
                .stream().map(component -> requireOwned(component.getBundleGameId(), sellerId)).toList();
        return new ProductFamily(product, parent, children, components, bundles);
    }

    /**
     * 소유자 확인까지 끝난 프로젝트.
     *
     * <p>빌드 등록도 같은 확인을 거쳐야 해서 밖으로 연다. 트랜잭션을 새로 열지 않고
     * <b>부르는 쪽의 트랜잭션에 참여</b>하므로, 빌드 저장과 소유 확인이 한 경계 안에 남는다.
     * 프로젝트 조회를 빌드 쪽에서 리포지토리로 직접 하면 같은 애그리거트를 만지는 클래스가 둘이 된다.
     */
    public GameProject requireOwned(Long gameId, Long sellerId) {
        GameProject project = requireById(gameId);
        project.requireOwner(sellerId);
        return project;
    }

    public GameProject requireById(Long gameId) {
        return projectRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "gameId=" + gameId));
    }

    private GameProject requireByProductCode(String productCode) {
        return projectRepository.findByProductCode(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "productCode=" + productCode));
    }
}
