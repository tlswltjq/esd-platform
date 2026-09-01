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
 * 다운로드 인증 → 서명 URL 발급. 애그리거트 셋을 가로지르는 유일한 유스케이스이고,
 * 권한 사본으로 판정하므로 license 장애와 무관하게 동작한다.
 * 파사드가 아니라 {@code core.service} 인 이유는 docs/code-notes.md
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
