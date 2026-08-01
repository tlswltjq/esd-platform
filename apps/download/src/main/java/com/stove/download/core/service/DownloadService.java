package com.stove.download.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.download.core.domain.DownloadTicket;
import com.stove.download.core.domain.Entitlement;
import com.stove.download.core.domain.EntitlementRepository;
import com.stove.download.core.domain.PatchManifest;
import com.stove.download.core.domain.PatchManifestRepository;
import com.stove.download.core.domain.ProductRef;
import com.stove.download.core.domain.ProductRefRepository;
import com.stove.download.core.domain.SignedUrl;
import com.stove.download.core.port.DownloadUrlSigner;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 배포/다운로드 유스케이스.
 * 모든 쓰기 경로가 문서 ID 고정 upsert 라 이벤트 중복 수신에 자연 멱등이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private final PatchManifestRepository manifestRepository;
    private final EntitlementRepository entitlementRepository;
    private final ProductRefRepository productRefRepository;
    private final DownloadUrlSigner downloadUrlSigner;

    /** studio → BuildUploaded : 패치 매니페스트 등록 */
    public void registerManifest(BuildUploadedEvent event) {
        manifestRepository.save(PatchManifest.from(event));
        log.info("매니페스트 등록 productCode={} version={}", event.productCode(), event.version());
    }

    /** catalog → ProductChanged : productCode ↔ productId 참조 갱신 */
    public void upsertProductRef(ProductChangedEvent event) {
        productRefRepository.save(ProductRef.builder()
                .id(event.productCode())
                .productId(event.productId())
                .name(event.name())
                .status(event.status())
                .build());
    }

    /** license → LicenseIssued : 다운로드 권한 부여 */
    public void grant(String orderNo, Long memberId, List<Long> productIds) {
        productIds.forEach(productId ->
                entitlementRepository.save(Entitlement.granted(memberId, productId, orderNo)));
        log.info("다운로드 권한 부여 memberId={} products={}", memberId, productIds);
    }

    /** license → LicenseRevoked : 환불 시 권한 회수 */
    public void revoke(Long memberId, List<Long> productIds) {
        productIds.forEach(productId -> entitlementRepository
                .findById(Entitlement.documentId(memberId, productId))
                .ifPresent(entitlement -> entitlementRepository.save(entitlement.revoke())));
        log.info("다운로드 권한 회수 memberId={} products={}", memberId, productIds);
    }

    /**
     * 다운로드 인증 → 서명 URL 발급.
     * 권한 사본으로 소유 여부를 판정하므로 license 장애와 무관하게 동작한다.
     */
    public DownloadTicket issueTicket(String productCode, Long memberId) {
        ProductRef ref = productRefRepository.findById(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "productCode=" + productCode));

        boolean owned = entitlementRepository.findById(Entitlement.documentId(memberId, ref.getProductId()))
                .map(Entitlement::isActive)
                .orElse(false);
        if (!owned) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "보유하지 않은 상품입니다: " + productCode);
        }

        PatchManifest latest = manifestRepository.findByProductCodeOrderByReleasedAtDesc(productCode).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "배포된 빌드가 없습니다."));

        SignedUrl signed = downloadUrlSigner.sign(latest.getStoragePath(), memberId);
        return DownloadTicket.of(latest, signed);
    }

    public List<PatchManifest> getManifests(String productCode) {
        return manifestRepository.findByProductCodeOrderByReleasedAtDesc(productCode);
    }
}
