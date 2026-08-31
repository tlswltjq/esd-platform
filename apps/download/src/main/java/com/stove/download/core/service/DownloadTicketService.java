package com.stove.download.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.download.core.domain.DownloadTicket;
import com.stove.download.core.domain.PatchManifest;
import com.stove.download.core.domain.ProductRef;
import com.stove.download.core.domain.SignedUrl;
import com.stove.download.core.port.DownloadUrlSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 다운로드 인증 → 서명 URL 발급.
 *
 * <p>이 모듈에서 애그리거트 셋을 <b>가로지르는 유일한 유스케이스</b>다 —
 * 참조로 productId 를 찾고(ProductRef), 보유를 판정하고(Entitlement), 최신 빌드를 집는다(Manifest).
 * 권한 사본으로 판정하므로 license 장애와 무관하게 동작한다.
 *
 * <p><b>왜 {@code api.application} 이 아니라 여기인가.</b> 파사드는 트랜잭션 밖에 두어야 할 것이
 * 있을 때만 만든다(결정 3). 이 경로에는 트랜잭션 자체가 없고(MongoDB, 결정 8),
 * "보유하지 않으면 받을 수 없다" 는 판정은 어댑터의 사정이 아니라 이 도메인의 규칙이다.
 * 조율이라는 이유만으로 core 밖으로 내보내면 그 규칙이 진입점마다 갈린다.
 *
 * <p>애그리거트별 접근은 각 서비스가 소유하고 여기서는 순서만 잡는다.
 * 셋을 직접 리포지토리로 읽으면 같은 애그리거트를 만지는 클래스가 둘이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadTicketService {

    private final ProductRefService productRefService;
    private final EntitlementService entitlementService;
    private final ManifestService manifestService;
    private final DownloadUrlSigner downloadUrlSigner;

    public DownloadTicket issue(String productCode, Long memberId) {
        ProductRef ref = productRefService.require(productCode);

        if (!entitlementService.owns(memberId, ref.getProductId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "보유하지 않은 상품입니다: " + productCode);
        }

        PatchManifest latest = manifestService.latest(productCode);
        SignedUrl signed = downloadUrlSigner.sign(latest.getStoragePath(), memberId);
        return DownloadTicket.of(latest, signed);
    }
}
