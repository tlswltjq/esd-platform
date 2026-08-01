package com.stove.order.infrastructure.client;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import com.stove.order.api.application.port.CatalogPort;
import com.stove.order.api.application.port.CatalogQuote;
import com.stove.order.api.application.port.QuoteItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link CatalogPort} 의 HTTP 어댑터. 주문 생성 경로의 유일한 동기 의존이며,
 * 실패 시 주문을 만들지 않는다(가격 미확정 상태로 결제로 넘기지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogRestClient implements CatalogPort {

    private static final ParameterizedTypeReference<ApiResponse<CatalogQuote>> QUOTE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient catalogRestClient;

    @Override
    public CatalogQuote quote(List<QuoteItem> items) {
        try {
            ApiResponse<CatalogQuote> response = catalogRestClient.post()
                    .uri("/api/v1/products/quote")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new QuoteCommand(items))
                    .retrieve()
                    .body(QUOTE_TYPE);

            if (response == null || !response.success() || response.data() == null) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "상품 가격 조회 응답이 비정상입니다.");
            }
            return response.data();
        } catch (RestClientException e) {
            log.error("catalog quote 호출 실패", e);
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "상품 정보를 확인할 수 없습니다.");
        }
    }

    /** catalog 의 요청 바디 형식. 전송 형식이라 어댑터 안에 둔다. */
    private record QuoteCommand(List<QuoteItem> items) {
    }
}
