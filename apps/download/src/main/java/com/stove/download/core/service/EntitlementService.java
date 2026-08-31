package com.stove.download.core.service;

import com.stove.download.core.domain.Entitlement;
import com.stove.download.core.domain.EntitlementRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 다운로드 권한 사본 — 이 회원이 이 상품을 받을 수 있는가.
 *
 * <p>license 의 지급/회수 이벤트로만 유지된다. 문서 ID 가 {@code memberId:productId} 라
 * 어떤 순서로 몇 번 도착하든 최종 상태가 수렴한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final EntitlementRepository entitlementRepository;

    /** license → LicenseIssued : 다운로드 권한 부여 */
    public void grant(String orderNo, Long memberId, List<Long> productIds) {
        productIds.forEach(productId ->
                entitlementRepository.save(Entitlement.granted(memberId, productId, orderNo)));
        log.info("다운로드 권한 부여 memberId={} products={}", memberId, productIds);
    }

    /**
     * license → LicenseRevoked : 환불 시 권한 회수.
     *
     * <p>회수 대상이 <b>그 주문에서 온 권한인지</b> 확인한다. 권한 문서 키는 {@code memberId:productId}
     * 뿐이라, 환불 후 재구매한 사용자에게 옛 주문의 회수 이벤트가 지각 도착하면
     * 정상 구매한 권한을 거둬가기 때문이다.
     */
    public void revoke(String orderNo, Long memberId, List<Long> productIds) {
        productIds.forEach(productId -> entitlementRepository
                .findById(Entitlement.documentId(memberId, productId))
                .ifPresentOrElse(
                        entitlement -> revokeIfSameOrder(entitlement, orderNo, memberId, productId),
                        () -> log.info("회수할 권한이 없다 memberId={} productId={}", memberId, productId)));
    }

    /** 지금 유효한 권한인가. 회수된 것과 애초에 없는 것을 구분하지 않는다 — 어느 쪽이든 못 받는다. */
    public boolean owns(Long memberId, Long productId) {
        return entitlementRepository.findById(Entitlement.documentId(memberId, productId))
                .map(Entitlement::isActive)
                .orElse(false);
    }

    private void revokeIfSameOrder(Entitlement entitlement, String orderNo, Long memberId, Long productId) {
        if (!entitlement.belongsTo(orderNo)) {
            log.info("다른 주문의 권한이라 회수하지 않는다 memberId={} productId={} 보유주문={} 회수요청={}",
                    memberId, productId, entitlement.getOrderNo(), orderNo);
            return;
        }
        entitlementRepository.save(entitlement.revoke());
        log.info("다운로드 권한 회수 orderNo={} memberId={} productId={}", orderNo, memberId, productId);
    }
}
