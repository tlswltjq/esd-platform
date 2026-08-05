package com.stove.catalog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.catalog.config.ReindexProperties;
import com.stove.catalog.core.domain.ReindexPage;
import com.stove.catalog.core.service.ProductCommandService;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재색인 오케스트레이션 — <b>얼마나 빨리 끝나는가가 아니라 무엇을 밀어내지 않는가</b>.
 *
 * <p>Outbox 릴레이는 전 서비스가 공유하는 자원이다(실측 약 480 events/s).
 * 재색인이 전량을 한꺼번에 밀어 넣으면 그동안 정상 상태 변경 이벤트가 뒤에 줄을 선다.
 * 그래서 여기서 고정하는 것은 <b>페이지로 쪼개진다</b>는 것과
 * <b>두 번 동시에 돌지 않는다</b>는 것이다.
 *
 * <p>DB 를 띄우지 않는다. 확인하려는 것은 상품 조회가 아니라 조율 규칙이다.
 */
class ProductReindexFacadeTest {

    private final ProductCommandService commandService = mock(ProductCommandService.class);

    private ProductReindexFacade facadeWith(ReindexProperties properties) {
        return new ProductReindexFacade(commandService, properties);
    }

    /** 스로틀 대기를 0 으로 둔 설정. 조율 규칙만 볼 때 쓴다. */
    private static ReindexProperties noThrottle(int pageSize) {
        return new ReindexProperties(pageSize, Duration.ZERO);
    }

    @Test
    @DisplayName("페이지를 이어 붙여 전량을 발행한다")
    void reindexWalksEveryPage() {
        when(commandService.republishFrom(0L, 2)).thenReturn(new ReindexPage(2, 2L, true));
        when(commandService.republishFrom(2L, 2)).thenReturn(new ReindexPage(2, 4L, true));
        when(commandService.republishFrom(4L, 2)).thenReturn(new ReindexPage(1, 5L, false));

        int total = facadeWith(noThrottle(2)).reindexAll();

        assertThat(total).isEqualTo(5);
    }

    @Test
    @DisplayName("한 페이지로 끝나면 더 조회하지 않는다")
    void reindexStopsOnPartialPage() {
        when(commandService.republishFrom(0L, 500)).thenReturn(new ReindexPage(3, 3L, false));

        assertThat(facadeWith(noThrottle(500)).reindexAll()).isEqualTo(3);
    }

    @Test
    @DisplayName("빈 카탈로그에서도 끝난다")
    void reindexOnEmptyCatalogTerminates() {
        when(commandService.republishFrom(0L, 500)).thenReturn(new ReindexPage(0, 0L, false));

        assertThat(facadeWith(noThrottle(500)).reindexAll()).isZero();
    }

    @Test
    @DisplayName("페이지 사이에 설정된 만큼 대기한다 — 릴레이를 독점하지 않는다")
    void reindexThrottlesBetweenPages() {
        when(commandService.republishFrom(0L, 1)).thenReturn(new ReindexPage(1, 1L, true));
        when(commandService.republishFrom(1L, 1)).thenReturn(new ReindexPage(1, 2L, false));

        long startedAt = System.nanoTime();
        facadeWith(new ReindexProperties(1, Duration.ofMillis(150))).reindexAll();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // 페이지가 2개면 사이 간격은 1회다. 스로틀을 지우면 이 단언이 깨진다.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(150L);
    }

    @Test
    @DisplayName("[4-5] 재색인이 도는 중에 또 트리거하면 409 다")
    void concurrentReindexIsRejected() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> secondCall = new AtomicReference<>();

        // 첫 재색인을 한 페이지에서 붙잡아 둔다.
        when(commandService.republishFrom(anyLong(), anyInt())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new ReindexPage(1, 1L, false);
        });

        ProductReindexFacade facade = facadeWith(noThrottle(500));
        Thread first = new Thread(facade::reindexAll);
        first.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // 트리거가 HTTP 라 운영자가 두 번 누르거나 두 명이 동시에 누를 수 있다.
        // 가드가 없으면 같은 일이 두 배로 돌아 릴레이 적체도 두 배가 된다.
        try {
            facade.reindexAll();
        } catch (Throwable t) {
            secondCall.set(t);
        }

        release.countDown();
        first.join(5_000);

        assertThat(secondCall.get())
                .as("중복 기동이 막히지 않았다")
                .isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) secondCall.get()).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("재색인이 끝나면 다시 트리거할 수 있다 — 가드가 걸린 채 남지 않는다")
    void guardIsReleasedAfterCompletion() {
        when(commandService.republishFrom(anyLong(), anyInt()))
                .thenReturn(new ReindexPage(1, 1L, false));
        ProductReindexFacade facade = facadeWith(noThrottle(500));

        facade.reindexAll();

        assertThat(facade.reindexAll()).isEqualTo(1);
    }

    @Test
    @DisplayName("재색인이 실패해도 가드가 풀린다 — 한 번 실패하면 영영 못 도는 상태를 막는다")
    void guardIsReleasedAfterFailure() {
        when(commandService.republishFrom(anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("DB 장애"));
        ProductReindexFacade facade = facadeWith(noThrottle(500));

        assertThatThrownBy(facade::reindexAll).isInstanceOf(IllegalStateException.class);

        // 두 번째 시도는 CONFLICT 가 아니라 원래 오류여야 한다.
        assertThatThrownBy(facade::reindexAll).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("설정이 비어 있으면 기본값(500건 / 200ms)으로 떨어진다")
    void propertiesFallBackToDefaults() {
        ReindexProperties defaults = new ReindexProperties(0, null);

        assertThat(defaults.pageSize()).isEqualTo(500);
        assertThat(defaults.pageInterval()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("커서가 페이지마다 앞으로 간다 — 같은 페이지를 반복하지 않는다")
    void cursorAdvancesEveryPage() {
        List<Long> cursors = new ArrayList<>();
        when(commandService.republishFrom(anyLong(), anyInt())).thenAnswer(invocation -> {
            long cursor = invocation.getArgument(0);
            cursors.add(cursor);
            return cursor < 2L
                    ? new ReindexPage(1, cursor + 1, true)
                    : new ReindexPage(1, cursor + 1, false);
        });

        facadeWith(noThrottle(1)).reindexAll();

        // 커서가 제자리면 무한 루프다. 반복이 끝났다는 것 자체가 절반의 증명이고,
        // 나머지 절반은 커서가 실제로 전진했다는 것이다.
        assertThat(cursors).containsExactly(0L, 1L, 2L);
    }
}
