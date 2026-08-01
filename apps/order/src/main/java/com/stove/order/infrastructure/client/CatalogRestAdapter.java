package com.stove.order.infrastructure.client;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import com.stove.order.core.domain.Quote;
import com.stove.order.core.domain.QuoteItem;
import com.stove.order.core.port.CatalogPort;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link CatalogPort} 의 HTTP 어댑터. 주문 생성 경로의 유일한 동기 의존이며,
 * 실패 시 주문을 만들지 않는다(가격 미확정 상태로 결제로 넘기지 않는다).
 *
 * <p>대상 주소와 타임아웃은 이 어댑터가 직접 조립한다. 상관관계 ID 전파 같은 횡단 관심사는
 * {@code RestClientCustomizer} 로 이미 빌더에 적용되어 들어온다.
 * 타임아웃을 명시하는 이유는 상류 지연이 주문 스레드를 잠식하지 않게 하기 위함이다.
 */
@Slf4j
@Component
public class CatalogRestAdapter implements CatalogPort {

    private static final ParameterizedTypeReference<ApiResponse<Quote>> QUOTE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public CatalogRestAdapter(RestClient.Builder builder, CatalogProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }

    @Override
    public Quote quote(List<QuoteItem> items) {
        try {
            ApiResponse<Quote> response = restClient.post()
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
