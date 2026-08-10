package com.stove.download.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.download.core.domain.DownloadTicket;
import com.stove.download.core.domain.Entitlement;
import com.stove.download.core.domain.EntitlementRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 다운로드 권한 사본의 수렴성.
 *
 * <p>download 는 자기 트랜잭션 없이 license/catalog/studio 세 서비스의 이벤트만으로
 * 상태를 만든다. 이벤트는 순서도 횟수도 보장되지 않으므로, 어떤 순서로 몇 번 도착하든
 * 최종 상태가 같아야 한다(수렴). 그 성질이 깨지는 지점을 찾는다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.Mongo.class, InfraContainers.Kafka.class})
class DownloadEntitlementTest {

    private static final AtomicLong SEQ = new AtomicLong(1);

    @Autowired
    DownloadService downloadService;
    @Autowired
    EntitlementRepository entitlementRepository;

    private final Long memberId = SEQ.incrementAndGet();
    private final Long productId = SEQ.incrementAndGet();
    private final String productCode = "GAME-" + UUID.randomUUID();

    /** catalog → ProductChanged, studio → BuildUploaded 가 도착한 상태를 만든다. */
    private void productIsPublished() {
        downloadService.upsertProductRef(ProductChangedEvent.of(
                productId, productCode, "게임 A", 1001L, 30_000L, "KRW", "ON_SALE", "ALL"));
        downloadService.registerManifest(BuildUploadedEvent.of(
                1L, productCode, "1.0.0", 1024L, "abc123", "s3://bucket/" + productCode));
    }

    private boolean isActive() {
        return entitlementRepository.findById(Entitlement.documentId(memberId, productId))
                .map(Entitlement::isActive)
                .orElse(false);
    }

    @Test
    @DisplayName("권한이 있으면 서명된 다운로드 티켓을 발급한다")
    void issuesTicketForOwnedProduct() {
        productIsPublished();
        downloadService.grant("ORD-1", memberId, List.of(productId));

        DownloadTicket ticket = downloadService.issueTicket(productCode, memberId);

        assertThat(ticket).isNotNull();
    }

    @Test
    @DisplayName("권한이 없으면 티켓을 발급하지 않는다")
    void rejectsUnownedProduct() {
        productIsPublished();

        assertThatThrownBy(() -> downloadService.issueTicket(productCode, memberId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("회수된 권한으로는 티켓을 발급하지 않는다")
    void rejectsRevokedEntitlement() {
        productIsPublished();
        downloadService.grant("ORD-1", memberId, List.of(productId));
        downloadService.revoke("ORD-1", memberId, List.of(productId));

        assertThatThrownBy(() -> downloadService.issueTicket(productCode, memberId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("같은 지급 이벤트를 여러 번 받아도 권한은 한 벌로 수렴한다")
    void grantIsIdempotent() {
        productIsPublished();

        downloadService.grant("ORD-1", memberId, List.of(productId));
        downloadService.grant("ORD-1", memberId, List.of(productId));

        assertThat(entitlementRepository.findById(Entitlement.documentId(memberId, productId)))
                .isPresent();
        assertThat(isActive()).isTrue();
    }

    @Test
    @DisplayName("회수 이벤트가 중복 도착해도 상태는 그대로다")
    void revokeIsIdempotent() {
        productIsPublished();
        downloadService.grant("ORD-1", memberId, List.of(productId));

        downloadService.revoke("ORD-1", memberId, List.of(productId));
        assertThatCode(() -> downloadService.revoke("ORD-1", memberId, List.of(productId)))
                .doesNotThrowAnyException();

        assertThat(isActive()).isFalse();
    }

    @Test
    @DisplayName("환불 후 재구매하면 권한이 되살아난다")
    void repurchaseRestoresEntitlement() {
        productIsPublished();
        downloadService.grant("ORD-1", memberId, List.of(productId));
        downloadService.revoke("ORD-1", memberId, List.of(productId));

        downloadService.grant("ORD-2", memberId, List.of(productId));

        assertThat(isActive()).isTrue();
    }

    @Test
    @DisplayName("[D-012] 옛 주문의 회수 이벤트가 새 주문으로 얻은 권한을 거둬가지 않는다")
    void staleRevokeMustNotAffectNewEntitlement() {
        productIsPublished();

        // 주문1 구매 → 환불
        downloadService.grant("ORD-1", memberId, List.of(productId));
        downloadService.revoke("ORD-1", memberId, List.of(productId));

        // 주문2 로 같은 게임을 다시 구매
        downloadService.grant("ORD-2", memberId, List.of(productId));
        assertThat(isActive()).isTrue();

        // 주문1 의 LicenseRevoked 가 지각 도착하거나 재전송된다.
        downloadService.revoke("ORD-1", memberId, List.of(productId));

        // 수정 전에는 회수됐다. 사용자는 정상 구매한 게임을 다운로드할 수 없게 되고,
        // 라이선스 서비스에는 ACTIVE 로 남아 있어 원인 추적이 어려웠다.
        assertThat(isActive()).as("주문2 로 얻은 권한").isTrue();
        assertThat(entitlementRepository.findById(Entitlement.documentId(memberId, productId))
                .orElseThrow().getOrderNo()).isEqualTo("ORD-2");
    }

    @Test
    @DisplayName("[D-012] 해당 주문의 회수 이벤트는 정상적으로 권한을 거둔다")
    void revokeOfOwningOrderStillWorks() {
        productIsPublished();
        downloadService.grant("ORD-1", memberId, List.of(productId));
        downloadService.revoke("ORD-1", memberId, List.of(productId));
        downloadService.grant("ORD-2", memberId, List.of(productId));

        downloadService.revoke("ORD-2", memberId, List.of(productId));

        assertThat(isActive()).isFalse();
    }

    @Test
    @DisplayName("상품 참조가 아직 안 왔으면 티켓 발급이 실패한다 — 이벤트 도착 순서 의존")
    void ticketNeedsProductReference() {
        // 권한은 있는데 catalog 의 ProductChanged 가 아직 도착하지 않은 구간.
        // 구매 직후 짧게 존재할 수 있는 상태이며, 이 경로에는 재시도 안내가 없다.
        downloadService.grant("ORD-1", memberId, List.of(productId));

        assertThatThrownBy(() -> downloadService.issueTicket(productCode, memberId))
                .isInstanceOf(BusinessException.class);
    }
}
