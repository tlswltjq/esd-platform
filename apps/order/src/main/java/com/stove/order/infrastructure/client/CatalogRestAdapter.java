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
 * <p>대상 주소와 타임아웃은 이 어댑터가 직접 조립한다. 자동 구성된 {@code RestClient.Builder} 는
 * 프로토타입이라 주입 지점마다 새 빌더가 오므로, 이름 붙은 {@code RestClient} 빈을 따로 두지 않는다.
 * 타임아웃을 명시하는 이유는 상류 지연이 주문 스레드를 잠식하지 않게 하기 위함이다.
 *
 * <p>추적 컨텍스트 전파는 여기서 다루지 않는다 — 그 빌더에 옵저베이션이 이미 걸려 있어
 * {@code traceparent} 가 자동으로 실린다. 예전에는 {@code RestClientCustomizer} 로
 * {@code X-Correlation-Id} 를 손수 붙였는데, 표준 헤더가 같은 일을 하므로 걷어냈다.
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
