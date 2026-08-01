package com.stove.order.core.port;

import com.stove.order.core.domain.Quote;
import com.stove.order.core.domain.QuoteItem;
import java.util.List;

/**
 * 외부 도메인(catalog)에 대한 포트.
 *
 * <p>지금은 조율(파사드)에서만 쓰이지만 포트는 예외 없이 core 가 소유한다 —
 * "확정된 가격이 필요하다"는 주문 도메인의 요구이지 어댑터의 사정이 아니기 때문이다.
 * 구현체는 같은 모듈의 아웃바운드 어댑터이며, 앱 사이에 컴파일 의존은 만들지 않는다.
 */
public interface CatalogPort {

    /** 가격 재계산. 실패 시 주문을 만들지 않는다(가격 미확정 상태로 결제로 넘기지 않는다). */
    Quote quote(List<QuoteItem> items);
}
