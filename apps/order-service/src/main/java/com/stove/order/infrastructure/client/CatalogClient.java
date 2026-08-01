package com.stove.order.infrastructure.client;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import com.stove.common.event.payload.OrderLine;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * catalog-service 동기 호출. 주문 생성 경로의 유일한 동기 의존이며,
 * 실패 시 주문을 만들지 않는다(가격 미확정 상태로 결제로 넘기지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogClient {

    private static final ParameterizedTypeReference<ApiResponse<QuoteResult>> QUOTE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient catalogRestClient;

    public QuoteResult quote(List<QuoteItem> items) {
        try {
            ApiResponse<QuoteResult> response = catalogRestClient.post()
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

    public record QuoteCommand(List<QuoteItem> items) {
    }

    public record QuoteItem(Long productId, int quantity) {
    }

    public record QuoteResult(List<OrderLine> lines, long totalAmount, String currency) {
    }
}
