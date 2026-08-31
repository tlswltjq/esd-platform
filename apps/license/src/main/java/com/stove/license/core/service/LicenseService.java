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
 * 라이선스 지급/회수. 멱등을 존재 확인과 DB 유니크 제약으로 이중으로 건다.
 * docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {

    private static final String AGGREGATE = "License";

    /** Kafka 컨슈머 그룹이자 Inbox 멱등 키. 리스너도 이 상수를 참조한다 — {@code ConsumerGroupRules} 참고. */
    public static final String CONSUMER_GROUP = "license";

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

        // 새로 지급된 것이 없어도 현재 소유 상태를 알린다 — docs/code-notes.md
        List<Long> owned = licenseRepository.findByOrderNo(orderNo).stream()
                .filter(License::isActive)
                .map(License::getProductId)
                .toList();

        if (owned.isEmpty()) {
            // 소유하지 않은 상태를 '지급' 으로 알릴 수는 없다.
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
            // 변화가 없는데 이벤트를 내보내면 하위 서비스가 헛일을 한다.
            log.info("이미 회수된 주문 orderNo={}", orderNo);
            return;
        }

        // download 가 다운로드 권한을 즉시 회수할 수 있도록 알린다
        outboxRecorder.record(AGGREGATE, orderNo,
                LicenseRevokedEvent.of(orderNo, licenses.get(0).getMemberId(), revoked, reason));

        log.info("라이선스 회수 orderNo={} count={} reason={}", orderNo, revoked.size(), reason);
    }

    /**
     * 지급 최종 실패 기록 + 보상 트리거. <b>{@code REQUIRES_NEW} 여야 한다</b> —
     * 지급 트랜잭션이 롤백된 뒤에 불리므로 별도 트랜잭션에서 커밋되어야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIssueFailure(String orderNo, Long memberId, String reason) {
        outboxRecorder.record(AGGREGATE, orderNo, LicenseIssueFailedEvent.of(orderNo, memberId, reason));
        log.error("라이선스 지급 최종 실패 → 보상 요청 orderNo={} reason={}", orderNo, reason);
    }

    /**
     * 이 주문에 라이선스가 발급된 이력이 있는가. 보상 환불 <b>직전에</b> 부른다. [D-028]
     * 회수된 것도 '있음' 으로 센다. docs/code-notes.md
     */
    @Transactional(readOnly = true)
    public boolean isIssued(String orderNo) {
        return !licenseRepository.findByOrderNo(orderNo).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<License> getLibrary(Long memberId) {
        return licenseRepository.findByMemberIdAndStatus(memberId, LicenseStatus.ACTIVE);
    }
}
