package com.stove.download.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.download.core.domain.DownloadTicket;
import com.stove.download.core.domain.PatchManifest;
import com.stove.download.core.domain.ProductRef;
import com.stove.download.core.domain.SignedUrl;
import com.stove.common.event.payload.BuildVariant;
import java.util.List;
import com.stove.download.core.port.DownloadUrlSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 다운로드 인증 → 서명 URL 발급. 애그리거트 셋을 가로지르는 유일한 유스케이스이고,
 * 권한 사본으로 판정하므로 license 장애와 무관하게 동작한다.
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
        return issue(productCode, memberId, null, null, null);
    }

    public DownloadTicket issue(String productCode, Long memberId, String platform,
                                String architecture, String fromVersion) {
        ProductRef ref = productRefService.require(productCode);

        if (!entitlementService.owns(memberId, ref.getProductId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "보유하지 않은 상품입니다: " + productCode);
        }

        if (ref.getReleaseId() == null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "릴리스 투영 동기화 중입니다. productCode=" + productCode);
        }
        PatchManifest latest = manifestService.requireRelease(productCode, ref.getReleaseId());
        if ((platform == null) != (architecture == null) || (fromVersion != null && platform == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "OS와 아키텍처를 함께 지정해야 하며 패치 기준 버전에도 필요합니다.");
        }
        List<BuildVariant> variants = latest.getBuildVariants();
        if (platform == null) {
            SignedUrl signed = downloadUrlSigner.sign(latest.getStoragePath(), memberId);
            return DownloadTicket.of(latest, signed);
        }
        if (variants == null || variants.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "이 릴리스에는 플랫폼별 빌드가 없습니다.");
        }
        List<BuildVariant> matching = variants.stream()
                .filter(variant -> platform.equals(variant.platform())
                        && architecture.equals(variant.architecture())).toList();
        BuildVariant selected = fromVersion == null ? null : matching.stream()
                .filter(variant -> fromVersion.equals(variant.deltaFromVersion())).findFirst().orElse(null);
        if (selected == null) {
            selected = matching.stream().filter(variant -> variant.deltaFromVersion() == null)
                    .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "해당 OS·아키텍처의 전체 빌드가 없습니다."));
        }
        SignedUrl signed = downloadUrlSigner.sign(selected.storagePath(), memberId);
        return DownloadTicket.of(latest, selected, signed);
    }
}
