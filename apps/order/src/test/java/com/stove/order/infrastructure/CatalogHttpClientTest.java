package com.stove.order.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.OrderErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

class CatalogHttpClientTest {
    private HttpServer server;
    private CatalogHttpClient client;
    private int status = 200;
    private String response;
    private long delay;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    @BeforeEach void start() throws Exception {
        response = "{\"success\":true,\"data\":{\"productId\":1,\"productCode\":\"Game-1\",\"title\":\"Game\",\"contentVersion\":3,\"price\":10000,\"currency\":\"KRW\",\"futureField\":true}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/v1/products/by-code/Game-1", exchange -> {
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        server.start();
        client = new CatalogHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 1000);
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    @Test void readsOnlyThePublicFieldsAndAllowsFutureFields() {
        var p = client.detail("Game-1");
        assertThat(p.price()).isEqualTo(10000);
        assertThat(p.contentVersion()).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(strings = {"404", "500", "403"})
    void mapsHttpFailures(String code) {
        status = Integer.parseInt(code);
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().statusCode()).isEqualTo(status == 404 ? 404 : status == 500 ? 503 : 502));
    }
    @ParameterizedTest @ValueSource(strings = {"price", "contentVersion", "productId"})
    void rejectsMissingOrFractionalNumbers(String field) {
        response = response.replaceFirst("\\\"" + field + "\\\":\\d+", "\"" + field + "\":1.5");
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.CATALOG_INVALID_RESPONSE));
        response = "{\"success\":true,\"data\":{}}";
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsWrongCodeCurrencyAndMalformedEnvelopes() {
        for (String invalid : new String[]{response.replace("Game-1", "game-1"), response.replace("KRW", "USD"),
                response.replace("true", "false"), "not-json", "{}"}) {
            response = invalid;
            assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode().statusCode()).isEqualTo(502));
        }
    }
    @Test void finiteReadTimeoutAndConnectionFailureReturnUnavailable() {
        delay = 500;
        client = new CatalogHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 100);
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().statusCode()).isEqualTo(503));
        server.stop(0);
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().statusCode()).isEqualTo(503));
    }
}
