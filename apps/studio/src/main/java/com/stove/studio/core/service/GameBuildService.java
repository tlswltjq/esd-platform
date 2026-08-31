package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.NewBuild;
import com.stove.studio.core.domain.UploadTicket;
import com.stove.studio.core.port.BuildStorage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게임 빌드 — 바이너리는 받지 않고 메타데이터와 업로드 자리만 관리한다.
 *
 * <p>등록이 곧 download 의 패치 매니페스트가 되므로 저장과 발행이 한 트랜잭션이어야 한다.
 * 그 원자성이 이 클래스의 분해 하한선이다 — 저장·발행을 더 쪼개면 둘을 다시 묶을 자리가
 * 파사드밖에 없는데, 파사드는 트랜잭션을 열지 않는다(결정 3·6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GameBuildService {

    private final GameBuildRepository buildRepository;
    private final GameProjectService gameProjectService;
    private final BuildStorage buildStorage;
    private final OutboxRecorder outboxRecorder;

    /**
     * 빌드 메타데이터 등록 → download 가 패치 매니페스트를 만든다.
     *
     * <p>이벤트는 빌드가 아니라 <b>프로젝트 애그리거트 스트림</b>에 적재한다
     * ({@code aggregateId = productCode}). 한 상품의 등록·심의·빌드가 한 줄로 늘어서야
     * 소비 측에서 순서를 따질 수 있다.
     */
    public GameBuild upload(Long gameId, Long sellerId, NewBuild request) {
        GameProject project = gameProjectService.requireOwned(gameId, sellerId);
        if (buildRepository.existsByGameIdAndVersion(gameId, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 등록된 버전입니다: " + request.version());
        }

        UploadTicket ticket =
                buildStorage.issueUploadTicket(project.getProductCode(), request.version());
        GameBuild build = buildRepository.save(GameBuild.of(gameId, request.version(),
                request.fileSize(), request.checksum(), ticket.storagePath()));

        outboxRecorder.record(GameProjectService.AGGREGATE, project.getProductCode(),
                BuildUploadedEvent.of(gameId, project.getProductCode(), request.version(),
                        request.fileSize(), request.checksum(), ticket.storagePath()));

        log.info("빌드 등록 gameId={} version={} size={}", gameId, request.version(), request.fileSize());
        return build;
    }

    @Transactional(readOnly = true)
    public List<GameBuild> findByGame(Long gameId) {
        return buildRepository.findByGameIdOrderByIdDesc(gameId);
    }
}
