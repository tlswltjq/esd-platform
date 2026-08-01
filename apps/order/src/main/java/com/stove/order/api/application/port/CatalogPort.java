package com.stove.order.api.application.port;

import java.util.List;

/**
 * 외부 도메인(catalog)에 대한 포트.
 *
 * <p>주문 조율에만 필요하므로 core 가 아니라 application 이 소유한다.
 * 구현체는 같은 모듈의 아웃바운드 어댑터이며, 앱 사이에 컴파일 의존은 만들지 않는다.
 */
public interface CatalogPort {

    /** 가격 재계산. 실패 시 주문을 만들지 않는다(가격 미확정 상태로 결제로 넘기지 않는다). */
    CatalogQuote quote(List<QuoteItem> items);
}
