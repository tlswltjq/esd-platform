package com.stove.download.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.download.core.domain.ProductRef;
import com.stove.download.core.domain.ProductRefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@code productCode ↔ productId} 참조 사본.
 *
 * <p>빌드는 productCode(스튜디오 기준)로, 라이선스는 productId(카탈로그 기준)로 온다.
 * 두 키를 잇는 것이 이 애그리거트의 전부이며, catalog 의 {@code ProductChanged} 로만 유지된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRefService {

    private final ProductRefRepository productRefRepository;

    /** catalog → ProductChanged : 참조 갱신. 문서 ID 가 productCode 라 재수신에 자연 멱등이다. */
    public void upsert(ProductChangedEvent event) {
        productRefRepository.save(ProductRef.builder()
                .id(event.productCode())
                .productId(event.productId())
                .name(event.name())
                .status(event.status())
                .build());
    }

    /**
     * 참조가 없으면 실패한다.
     *
     * <p>구매 직후 {@code ProductChanged} 가 아직 도착하지 않은 구간이 짧게 존재한다.
     * 그 상태를 조용히 "미보유" 로 흘리지 않기 위해 없는 것과 못 가진 것을 다른 예외로 가른다.
     */
    public ProductRef require(String productCode) {
        return productRefRepository.findById(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "productCode=" + productCode));
    }
}
