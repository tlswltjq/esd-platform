package com.stove.download.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.download.core.domain.PatchManifest;
import com.stove.download.core.domain.PatchManifestRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 패치 매니페스트 — 어떤 빌드가 배포돼 있는가.
 *
 * <p>문서 ID 가 {@code productCode:version} 이라 {@code BuildUploaded} 를 몇 번 받아도 같은 문서를
 * 덮어쓴다. 연산 자체가 멱등이라 Inbox 가드도 트랜잭션도 없다(결정 8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestService {

    private final PatchManifestRepository manifestRepository;

    /** studio → BuildUploaded : 패치 매니페스트 등록 */
    public void register(BuildUploadedEvent event) {
        manifestRepository.save(PatchManifest.from(event));
        log.info("매니페스트 등록 productCode={} version={}", event.productCode(), event.version());
    }

    /** 패치 이력. 최신순이며 보유 여부와 무관하게 열려 있다. */
    public List<PatchManifest> history(String productCode) {
        return manifestRepository.findByProductCodeOrderByReleasedAtDesc(productCode);
    }

    /**
     * 내려받을 최신 빌드.
     *
     * <p>"배포된 빌드가 있는가" 는 매니페스트 애그리거트의 질문이므로 없을 때의 판단도 여기 둔다.
     * 티켓 발급 쪽으로 옮기면 같은 판정이 조회 경로와 갈린다.
     */
    public PatchManifest latest(String productCode) {
        return history(productCode).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "배포된 빌드가 없습니다."));
    }
}
