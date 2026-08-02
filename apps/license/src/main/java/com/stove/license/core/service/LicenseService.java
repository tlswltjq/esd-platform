package com.stove.license.core.service;

import com.stove.common.event.payload.LicenseIssueFailedEvent;
import com.stove.common.event.payload.LicenseIssuedEvent;
import com.stove.common.event.payload.LicenseRevokedEvent;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이선스 지급/회수.
 *
 * <p>지급은 결제 완료 이벤트로만 발생하며, 같은 주문·상품에 대해 몇 번 호출되어도
 * 결과가 같도록(멱등) 존재 여부 확인 + DB 유니크 제약을 이중으로 건다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {

    private static final String AGGREGATE = "License";
    private static final String CONSUMER_GROUP = "license";

    private final LicenseRepository licenseRepository;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;

    /** [결제] payment → PaymentCompleted → license (발급) */
    @Transactional
    public void issue(String eventId, String eventType, String orderNo, Long memberId, List<OrderLine> lines) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }

        for (Long productId : lines.stream().map(OrderLine::productId).distinct().toList()) {
            if (licenseRepository.existsByOrderNoAndProductId(orderNo, productId)) {
                continue;
            }
            licenseRepository.save(License.issue(orderNo, memberId, productId));
        }

        // 새로 지급된 것이 없어도 현재 소유 상태를 알린다.
        // download 는 자기 DB 없이 이 이벤트만으로 권한 사본을 만들기 때문에,
        // '변화'만 실으면 이벤트를 한 번 놓친 하위 서비스를 재처리로 복구할 방법이 없다.
        // 수신 측은 문서 ID 고정 upsert 라 같은 상태를 여러 번 받아도 안전하다.
        List<Long> owned = licenseRepository.findByOrderNo(orderNo).stream()
                .filter(License::isActive)
                .map(License::getProductId)
                .toList();

        if (owned.isEmpty()) {
            // 이미 전부 회수된 주문에 지급 이벤트가 뒤늦게 들어온 경우.
            // 소유하지 않은 상태를 '지급'으로 알릴 수는 없다.
            log.warn("보유 중인 라이선스가 없어 지급 이벤트를 발행하지 않는다 orderNo={}", orderNo);
            return;
        }

        outboxRecorder.record(AGGREGATE, orderNo, LicenseIssuedEvent.of(orderNo, memberId, owned));
        log.info("라이선스 지급 orderNo={} products={}", orderNo, owned);
    }

    /** [환불] payment → PaymentCancelled → license (회수) */
    @Transactional
    public void revoke(String eventId, String eventType, String orderNo, String reason) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }
        List<License> licenses = licenseRepository.findByOrderNo(orderNo);
        if (licenses.isEmpty()) {
            return;
        }

        List<Long> revoked = licenses.stream()
                .filter(license -> license.revoke(reason))
                .map(License::getProductId)
                .toList();

        if (revoked.isEmpty()) {
            // 이미 전부 회수된 상태다. 변화가 없는데 이벤트를 내보내면 하위 서비스가 헛일을 한다.
            log.info("이미 회수된 주문 orderNo={}", orderNo);
            return;
        }

        // download 가 다운로드 권한을 즉시 회수할 수 있도록 알린다
        outboxRecorder.record(AGGREGATE, orderNo,
                LicenseRevokedEvent.of(orderNo, licenses.get(0).getMemberId(), revoked, reason));

        log.info("라이선스 회수 orderNo={} count={} reason={}", orderNo, revoked.size(), reason);
    }

    /**
     * 지급 최종 실패 기록 + 보상 트랜잭션 트리거.
     *
     * <p>{@code REQUIRES_NEW} 인 이유: 지급 트랜잭션이 롤백된 뒤에 호출되므로
     * 실패 이벤트만큼은 반드시 별도 트랜잭션에서 커밋되어야 payment 가 환불을 시작할 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIssueFailure(String orderNo, Long memberId, String reason) {
        outboxRecorder.record(AGGREGATE, orderNo, LicenseIssueFailedEvent.of(orderNo, memberId, reason));
        log.error("라이선스 지급 최종 실패 → 보상 요청 orderNo={} reason={}", orderNo, reason);
    }

    @Transactional(readOnly = true)
    public List<License> getLibrary(Long memberId) {
        return licenseRepository.findByMemberIdAndStatus(memberId, LicenseStatus.ACTIVE);
    }
}
