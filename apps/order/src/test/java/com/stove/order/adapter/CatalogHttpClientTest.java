package com.stove.order.adapter;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.order.config.CatalogClientConfiguration;
import com.stove.order.core.domain.OrderErrorCode;
import com.stove.order.core.domain.PublishedProduct;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogHttpClientTest {
    private HttpServer server;
    private CatalogHttpClient client;
    private int status = 200;
    private String response;
    private final AtomicInteger calls = new AtomicInteger();
    private long delay;
    private String seenKey;
    private String seenAuthorization;
    private String location;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    @BeforeEach void start() throws Exception {
        response = "{\"success\":true,\"data\":{\"productId\":1,\"productCode\":\"Game-1\",\"title\":\"Game\",\"contentVersion\":3,\"price\":10000,\"currency\":\"KRW\",\"saleStatus\":\"ON_SALE\",\"publicDetailAllowed\":true,\"futureField\":true}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/internal/v1/order-products/Game-1", exchange -> {
            calls.incrementAndGet();
            seenKey = exchange.getRequestHeaders().getFirst("X-Order-Service-Key");
            seenAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            try {
                if (location != null) exchange.getResponseHeaders().set("Location", location);
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        server.start();
        client = new CatalogClientConfiguration().catalogProducts("http://127.0.0.1:" + server.getAddress().getPort(), "order-test-key", 1000, 1000, RestClient.builder());
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    @Test void readsOnlyThePublicFieldsAndAllowsFutureFields() {
        PublishedProduct p = client.detail("Game-1");
        assertThat(p.price()).isEqualTo(10000);
        assertThat(p.contentVersion()).isEqualTo(3);
        assertThat(p.saleStatus()).isEqualTo("ON_SALE");
        assertThat(p.publicDetailAllowed()).isTrue();
        assertThat(seenKey).isEqualTo("order-test-key");
        assertThat(seenAuthorization).isNull();
    }
    @Test void hiddenFactsRemainReadableInternallyAndAccessEvidenceIsStrictlyTyped() {
        String original = response;
        response = original.replace("\"publicDetailAllowed\":true", "\"publicDetailAllowed\":false");
        assertThat(client.detail("Game-1").publicDetailAllowed()).isFalse();
        for (String invalid : new String[]{original.replace(",\"publicDetailAllowed\":true", ""),
                original.replace("\"publicDetailAllowed\":true", "\"publicDetailAllowed\":\"true\"")}) {
            response = invalid;
            assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode().statusCode()).isEqualTo(502));
        }
    }
    @Test void missingServiceKeyFailsBeforeAnyNetworkRequest() {
        CatalogHttpClient unconfigured = new CatalogHttpClient(RestClient.create("http://127.0.0.1:" + server.getAddress().getPort()), "");
        assertThatThrownBy(() -> unconfigured.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().statusCode()).isEqualTo(503));
        assertThat(calls.get()).isZero();
    }
    @Test void redirectsNeverCarryTheServiceKeyToAnotherRequest() {
        status = 302;
        location = "http://127.0.0.1:" + server.getAddress().getPort() + "/leak";
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().statusCode()).isEqualTo(502));
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void readsStoppedSalesAndRejectsMissingOrUnknownSaleStates() {
        String original = response;
        response = original.replace("ON_SALE", "STOPPED");
        assertThat(client.detail("Game-1").saleStatus()).isEqualTo("STOPPED");
        for (String invalid : new String[]{original.replace(",\"saleStatus\":\"ON_SALE\"", ""),
                original.replace("ON_SALE", "UNKNOWN")}) {
            response = invalid;
            assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(OrderErrorCode.CATALOG_INVALID_RESPONSE));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"404", "500", "401", "403"})
    void mapsHttpFailures(String code) {
        status = Integer.parseInt(code);
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().statusCode()).isEqualTo(status == 404 ? 404 : 503));
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
        client = new CatalogClientConfiguration().catalogProducts("http://127.0.0.1:" + server.getAddress().getPort(), "order-test-key", 1000, 100, RestClient.builder());
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().statusCode()).isEqualTo(503));
        server.stop(0);
        assertThatThrownBy(() -> client.detail("Game-1")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().statusCode()).isEqualTo(503));
    }
}
