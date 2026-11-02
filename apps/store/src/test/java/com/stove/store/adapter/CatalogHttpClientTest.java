package com.stove.store.adapter;

import static com.stove.store.StoreFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.response.ApiResponse;
import com.stove.store.config.CatalogClientConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CatalogHttpClientTest {
    @Test void readsExistingPublicEnvelopesWithoutDependingOnCatalogClasses() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CatalogProductSnapshot product = product(1, 3, "한글 게임", 2000);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean redirect = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            if (redirect.get()) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/leak");
                exchange.sendResponseHeaders(302, -1); exchange.close(); return;
            }
            key.set(exchange.getRequestHeaders().getFirst("X-Store-Service-Key"));
            path.set(exchange.getRequestURI().getPath());
            Object data = exchange.getRequestURI().getPath().startsWith("/internal/v1/store-products/") ? product
                    : Map.of("content", List.of(Map.of("productCode", product.productCode())));
            byte[] body = mapper.writeValueAsBytes(ApiResponse.ok(data));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            CatalogHttpClient client = new CatalogClientConfiguration().catalogProducts("http://127.0.0.1:" + server.getAddress().getPort(), "store-test-key", mapper);
            CatalogHttpClient unconfigured = new CatalogClientConfiguration().catalogProducts("http://127.0.0.1:" + server.getAddress().getPort(), "", mapper);
            assertThatThrownBy(() -> unconfigured.detail(product.productCode())).isInstanceOf(IllegalStateException.class);
            assertThat(calls.get()).isZero();
            assertThat(client.codes(0)).containsExactly(product.productCode());
            assertThat(path.get()).isEqualTo("/internal/v1/store-products");
            assertThat(key.get()).isEqualTo("store-test-key");
            assertThat(client.detail(product.productCode())).isEqualTo(product);
            assertThat(key.get()).isEqualTo("store-test-key");
            assertThat(path.get()).isEqualTo("/internal/v1/store-products/" + product.productCode());
            assertThatThrownBy(() -> client.detail("wrong-code")).isInstanceOf(IllegalStateException.class);
            int before = calls.get(); redirect.set(true);
            assertThatThrownBy(() -> client.codes(0)).isInstanceOf(IllegalStateException.class);
            assertThat(calls.get()).isEqualTo(before + 1);
        } finally { server.stop(0); }
    }
}
