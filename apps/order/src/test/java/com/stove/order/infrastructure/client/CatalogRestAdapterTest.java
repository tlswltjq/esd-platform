package com.stove.order.infrastructure.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.order.core.domain.Quote;
import com.stove.order.core.domain.QuoteItem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * catalog 동기 호출의 실패 처리. <b>주문 경로의 유일한 외부 HTTP 의존</b>이다.
 *
 * <p>여기서 중요한 것은 성공 경로가 아니라 <b>실패했을 때 무엇이 되는가</b>다.
 * catalog 가 죽었을 때 주문이 만들어지면 <b>가격이 확정되지 않은 주문</b>이
 * 결제로 넘어간다 — 금액의 단일 진실 공급원이 사라진 채로 돈이 움직인다.
 * 그래서 어떤 실패든 예외로 끝나야 하고, 그 사실을 실패 유형별로 고정한다.
 *
 * <p>실제 소켓을 쓰는 이유는 <b>타임아웃과 커넥션 거부는 대역으로 재현할 수 없기 때문</b>이다.
 * {@code RestClient} 의 요청 팩토리를 어댑터가 직접 조립하므로
 * {@code MockRestServiceServer} 로는 가로챌 수도 없다.
 */
class CatalogRestAdapterTest {

    private static final List<QuoteItem> ITEMS = List.of(new QuoteItem(1L, 2));

    private WireMockServer catalog;

    @BeforeEach
    void startCatalog() {
        // HTTP/2 평문을 끈다. JDK HttpClient 가 h2c 로 업그레이드를 시도하면
        // 모든 요청이 RST_STREAM 으로 끊겨 <b>실패 경로 테스트가 엉뚱한 이유로 통과</b>한다 —
        // 5xx 를 검증한다고 믿는 테스트가 실제로는 커넥션 오류를 보고 있게 된다.
        catalog = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .http2PlainDisabled(true));
        catalog.start();
    }

    @AfterEach
    void stopCatalog() {
        catalog.stop();
    }

    /** 읽기 타임아웃을 짧게 줘야 지연 테스트가 몇 초씩 걸리지 않는다. */
    private CatalogRestAdapter adapterWithReadTimeout(Duration readTimeout) {
        return new CatalogRestAdapter(RestClient.builder(), new CatalogProperties(
                "http://localhost:" + catalog.port(), Duration.ofMillis(200), readTimeout));
    }

    private CatalogRestAdapter adapter() {
        return adapterWithReadTimeout(Duration.ofSeconds(2));
    }

    private void catalogResponds(int status, String body) {
        catalog.stubFor(post(urlEqualTo("/api/v1/products/quote"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    @DisplayName("정상 응답이면 서버가 확정한 금액이 그대로 돌아온다")
    void returnsServerConfirmedQuote() {
        catalogResponds(200, """
                {"success":true,"data":{
                  "lines":[{"productId":1,"productName":"게임 A","sellerId":1001,"unitPrice":30000,"quantity":2}],
                  "totalAmount":60000,"currency":"KRW"}}
                """);

        Quote quote = adapter().quote(ITEMS);

        assertThat(quote.totalAmount()).isEqualTo(60_000L);
        assertThat(quote.currency()).isEqualTo("KRW");
        assertThat(quote.lines()).hasSize(1);
    }

    @Test
    @DisplayName("catalog 가 5xx 를 주면 주문을 만들지 않는다")
    void serverErrorBecomesUpstreamUnavailable() {
        catalogResponds(500, "{}");

        assertThatThrownBy(() -> adapter().quote(ITEMS))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("catalog 가 4xx 를 줘도 마찬가지다 — 가격을 못 받은 것은 같다")
    void clientErrorBecomesUpstreamUnavailable() {
        catalogResponds(400, "{}");

        assertThatThrownBy(() -> adapter().quote(ITEMS))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("200 이어도 success=false 면 실패로 본다 — 상태코드만 믿지 않는다")
    void unsuccessfulBodyIsFailure() {
        catalogResponds(200, """
                {"success":false,"data":null,"error":{"code":"NOT_FOUND"}}
                """);

        assertThatThrownBy(() -> adapter().quote(ITEMS))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("success=true 인데 data 가 비어도 실패다 — 금액 없는 주문이 생기면 안 된다")
    void successWithoutDataIsFailure() {
        catalogResponds(200, """
                {"success":true,"data":null}
                """);

        assertThatThrownBy(() -> adapter().quote(ITEMS))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("응답이 늦으면 읽기 타임아웃으로 끊는다 — 상류 지연이 주문 스레드를 잠식하지 않는다")
    void slowResponseHitsReadTimeout() {
        catalog.stubFor(post(urlEqualTo("/api/v1/products/quote"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(2_000)
                        .withBody("{\"success\":true,\"data\":{}}")));

        assertThatThrownBy(() -> adapterWithReadTimeout(Duration.ofMillis(200)).quote(ITEMS))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("catalog 가 아예 떠 있지 않아도 예외로 끝난다")
    void connectionRefusedIsHandled() {
        int deadPort = catalog.port();
        catalog.stop();

        CatalogRestAdapter adapter = new CatalogRestAdapter(RestClient.builder(),
                new CatalogProperties("http://localhost:" + deadPort,
                        Duration.ofMillis(200), Duration.ofMillis(200)));

        assertThatThrownBy(() -> adapter.quote(ITEMS))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPSTREAM_UNAVAILABLE);
    }
}
