package com.stove.store.core.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.store.core.domain.ProductDocument;
import com.stove.store.core.domain.ProductSearchRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 캐시 애노테이션이 <b>실제로 걸려 있는가</b>.
 *
 * <p>{@link StoreIndexTest} 는 {@code new StoreService(repository)} 로 직접 만들어 쓴다.
 * 그러면 프록시가 없으므로 {@code @Cacheable}·{@code @CacheEvict} 가 전부 비활성이고,
 * <b>애노테이션을 지워도 그 테스트는 통과한다.</b> 캐시 무효화가 빠지면
 * 판매 종료된 상품이 메인 진열에 계속 남는데, 그것을 잡는 테스트가 없었다.
 *
 * <p>여기서는 프록시를 태운다. Redis 는 띄우지 않는다 — 확인하려는 것은
 * 캐시 저장소의 동작이 아니라 <b>선언이 적용되는가</b>이기 때문이다.
 * 저장소 구현이 무엇이든 프록시가 없으면 아래 두 테스트는 깨진다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = StoreCacheProxyTest.CachingTestConfig.class)
class StoreCacheProxyTest {

    @Configuration
    @EnableCaching
    static class CachingTestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("store:featured");
        }

        @Bean
        ProductSearchRepository productSearchRepository() {
            ProductSearchRepository repository = mock(ProductSearchRepository.class);
            when(repository.findByStatusOrderByPriceAsc(anyString(), any(Pageable.class)))
                    .thenReturn(List.of());
            return repository;
        }

        @Bean
        StoreService storeService(ProductSearchRepository repository) {
            return new StoreService(repository);
        }
    }

    @Autowired
    StoreService storeService;
    @Autowired
    ProductSearchRepository repository;

    private static ProductChangedEvent product(String status) {
        return ProductChangedEvent.of(1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", status, "ALL");
    }

    @Test
    @DisplayName("메인 진열은 두 번째 호출부터 캐시에서 나온다")
    void featuredIsServedFromCacheOnSecondCall() {
        storeService.featured();
        storeService.featured();

        // @Cacheable 이 걸려 있지 않으면 조회가 두 번 나간다.
        verify(repository, times(1))
                .findByStatusOrderByPriceAsc(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("색인이 갱신되면 메인 진열 캐시가 무효화된다")
    void indexingEvictsTheFeaturedCache() {
        storeService.featured();

        storeService.indexProduct(product("ON_SALE"));

        storeService.featured();

        // @CacheEvict 가 없으면 두 번째 featured() 도 캐시에서 나와 조회가 한 번뿐이다 —
        // 판매 상태가 바뀐 상품이 진열에서 그대로 살아 있다는 뜻이다.
        verify(repository, times(2))
                .findByStatusOrderByPriceAsc(anyString(), any(Pageable.class));
    }
}
