package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.GameProjectRepository;
import com.stove.studio.core.domain.NewBuild;
import com.stove.studio.core.domain.NewProject;
import com.stove.studio.core.domain.UploadTicket;
import com.stove.studio.core.port.BuildStorage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 크리에이터 셀프 퍼블리싱 유스케이스.
 * 심의 신청·빌드 업로드가 각각 다운스트림(review, download)의 시작점이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StudioService {

    private static final String AGGREGATE = "GameProject";
    private static final String CONSUMER_GROUP = "studio";

    private final GameProjectRepository projectRepository;
    private final GameBuildRepository buildRepository;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;
    private final BuildStorage buildStorage;

    public GameProject createProject(NewProject request) {
        projectRepository.findByProductCode(request.productCode()).ifPresent(p -> {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 존재하는 상품코드입니다.");
        });
        return projectRepository.save(GameProject.create(request.productCode(), request.title(),
                request.sellerId(), request.price(), request.currency(), request.selfRated()));
    }

    /** [등록] studio → GameRegistered → review */
    public void submitForReview(Long gameId, Long sellerId) {
        GameProject project = findProject(gameId);
        project.requireOwner(sellerId);
        project.submit();

        outboxRecorder.record(AGGREGATE, project.getProductCode(),
                GameRegisteredEvent.of(project.getId(), project.getProductCode(), project.getTitle(),
                        project.getSellerId(), project.getPrice(), project.getCurrency(), project.isSelfRated()));

        log.info("심의 신청 gameId={} productCode={} selfRated={}",
                gameId, project.getProductCode(), project.isSelfRated());
    }

    /** 빌드 메타데이터 등록 → download 가 패치 매니페스트를 만든다 */
    public GameBuild uploadBuild(Long gameId, Long sellerId, NewBuild request) {
        GameProject project = findProject(gameId);
        project.requireOwner(sellerId);
        if (buildRepository.existsByGameIdAndVersion(gameId, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 등록된 버전입니다: " + request.version());
        }

        UploadTicket ticket =
                buildStorage.issueUploadTicket(project.getProductCode(), request.version());
        GameBuild build = buildRepository.save(GameBuild.of(gameId, request.version(),
                request.fileSize(), request.checksum(), ticket.storagePath()));

        outboxRecorder.record(AGGREGATE, project.getProductCode(),
                BuildUploadedEvent.of(gameId, project.getProductCode(), request.version(),
                        request.fileSize(), request.checksum(), ticket.storagePath()));

        log.info("빌드 등록 gameId={} version={} size={}", gameId, request.version(), request.fileSize());
        return build;
    }

    /** review 승인 이벤트 반영 */
    public void applyApproval(String eventId, String eventType, String productCode, String ratingCode) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        findByProductCode(productCode).approve(ratingCode);
        log.info("심의 승인 반영 productCode={} rating={}", productCode, ratingCode);
    }

    /** review 반려 이벤트 반영 */
    public void applyRejection(String eventId, String eventType, String productCode, String reason) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        findByProductCode(productCode).reject(reason);
        log.info("심의 반려 반영 productCode={} reason={}", productCode, reason);
    }

    @Transactional(readOnly = true)
    public List<GameProject> getProjects(Long sellerId) {
        return projectRepository.findBySellerIdOrderByIdDesc(sellerId);
    }

    @Transactional(readOnly = true)
    public List<GameBuild> getBuilds(Long gameId) {
        return buildRepository.findByGameIdOrderByIdDesc(gameId);
    }

    private GameProject findProject(Long gameId) {
        return projectRepository.findById(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "gameId=" + gameId));
    }

    private GameProject findByProductCode(String productCode) {
        return projectRepository.findByProductCode(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "productCode=" + productCode));
    }
}
