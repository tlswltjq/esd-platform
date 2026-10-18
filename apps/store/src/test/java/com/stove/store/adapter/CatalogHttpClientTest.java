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
import org.junit.jupiter.api.Test;

class CatalogHttpClientTest {
    @Test void readsExistingPublicEnvelopesWithoutDependingOnCatalogClasses() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CatalogProductSnapshot product = product(1, 3, "한글 게임", 2000);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/products", exchange -> {
            Object data = exchange.getRequestURI().getPath().contains("by-code") ? product
                    : Map.of("content", List.of(Map.of("productCode", product.productCode())));
            byte[] body = mapper.writeValueAsBytes(ApiResponse.ok(data));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            CatalogHttpClient client = new CatalogClientConfiguration().catalogProducts("http://127.0.0.1:" + server.getAddress().getPort(), mapper);
            assertThat(client.codes(0)).containsExactly(product.productCode());
            assertThat(client.detail(product.productCode())).isEqualTo(product);
            assertThatThrownBy(() -> client.detail("wrong-code")).isInstanceOf(IllegalStateException.class);
        } finally { server.stop(0); }
    }
}
