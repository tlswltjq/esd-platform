package com.stove.download.api.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.Topics;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.event.payload.LicenseIssuedEvent;
import com.stove.common.event.payload.LicenseRevokedEvent;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.test.EventRecords;
import com.stove.download.core.service.EntitlementService;
import com.stove.download.core.service.ManifestService;
import com.stove.download.core.service.ProductRefService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * download 는 세 토픽을 동시에 듣는 유일한 서비스다 — studio · catalog · license.
 *
 * <p>토픽마다 리스너 메서드가 따로 있고 관심 이벤트도 다르다.
 * <b>토픽과 분기의 짝이 어긋나면 조용히 아무 일도 일어나지 않는다</b> —
 * 다운로드 권한이 안 생겨도 예외가 나지 않으므로, 사용자가 문의할 때까지 드러나지 않는다.
 * 그래서 "무엇에 반응하는가"와 "무엇에 반응하지 않는가"를 함께 고정한다.
 */
class DownloadEventListenerTest {

    private final ManifestService manifestService = mock(ManifestService.class);
    private final ProductRefService productRefService = mock(ProductRefService.class);
    private final EntitlementService entitlementService = mock(EntitlementService.class);
    private final DownloadEventListener listener = new DownloadEventListener(
            manifestService, productRefService, entitlementService, EventRecords.OBJECT_MAPPER);

    private static final List<Long> PRODUCT_IDS = List.of(1L, 2L);
    private static final LicenseIssuedEvent ISSUED =
            LicenseIssuedEvent.of("ORD-1", 42L, PRODUCT_IDS);
    private static final BuildUploadedEvent BUILD = BuildUploadedEvent.of(
            1L, "GAME-001", "1.0.0", 1024L, "sha256:abc", "s3://bucket/build");

    @Test
    @DisplayName("빌드 업로드는 패치 매니페스트로 등록된다")
    void buildUploadedRegistersManifest() {
        listener.onStudioEvent(EventRecords.of(Topics.STUDIO, BUILD));

        verify(manifestService).register(any(BuildUploadedEvent.class));
    }

    @Test
    @DisplayName("게임 등록은 download 의 관심사가 아니다 — 같은 토픽의 다른 이벤트")
    void gameRegisteredIsIgnored() {
        listener.onStudioEvent(EventRecords.of(Topics.STUDIO, GameRegisteredEvent.of(
                1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", false)));

        verifyNoInteractions(manifestService, productRefService, entitlementService);
    }

    @Test
    @DisplayName("상품 변경은 productCode ↔ productId 참조로 반영된다")
    void productChangedUpsertsReference() {
        listener.onCatalogEvent(EventRecords.of(Topics.CATALOG, ProductChangedEvent.of(
                1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", "ON_SALE", "ALL")));

        verify(productRefService).upsert(any(ProductChangedEvent.class));
    }

    @Test
    @DisplayName("라이선스 지급은 다운로드 권한 부여로 이어진다")
    void licenseIssuedGrantsAccess() {
        listener.onLicenseEvent(EventRecords.of(Topics.LICENSE, ISSUED));

        verify(entitlementService).grant(eq("ORD-1"), eq(42L), eq(PRODUCT_IDS));
    }

    @Test
    @DisplayName("라이선스 회수는 권한 회수로 이어진다")
    void licenseRevokedRemovesAccess() {
        listener.onLicenseEvent(EventRecords.of(Topics.LICENSE,
                LicenseRevokedEvent.of("ORD-1", 42L, PRODUCT_IDS, "USER_REFUND")));

        verify(entitlementService).revoke(eq("ORD-1"), eq(42L), eq(PRODUCT_IDS));
    }

    @Test
    @DisplayName("각 리스너는 관심 없는 eventType 에 반응하지 않는다")
    void unrelatedEventTypesAreIgnored() {
        listener.onStudioEvent(EventRecords.ofUnrelatedType(Topics.STUDIO));
        listener.onCatalogEvent(EventRecords.ofUnrelatedType(Topics.CATALOG));
        listener.onLicenseEvent(EventRecords.ofUnrelatedType(Topics.LICENSE));

        verifyNoInteractions(manifestService, productRefService, entitlementService);
    }

    @Test
    @DisplayName("권한 부여 중 일시 장애는 예외로 전파된다 — 삼키면 산 게임을 못 받는다")
    void propagatesTransientFailure() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(entitlementService).grant(anyString(), anyLong(), any());

        assertThatThrownBy(() -> listener.onLicenseEvent(EventRecords.of(Topics.LICENSE, ISSUED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
