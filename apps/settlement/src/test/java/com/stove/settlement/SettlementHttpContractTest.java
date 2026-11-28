package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.adapter.CatalogOwnerHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SettlementHttpContractTest {
    private HttpServer server;
    private final AtomicReference<String> response=new AtomicReference<String>();
    private RestClient client;
    @BeforeEach void server() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Settlement-Service-Key")).isEqualTo("read-only-key");
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);try(OutputStream out=exchange.getResponseBody()) { out.write(bytes); }
        });server.start();
        client=RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).defaultHeader("X-Settlement-Service-Key","read-only-key").build();
    }
    @AfterEach void close() { server.stop(0); }
    @Test void ownerLookupRequiresMessageAndMatchingProductIdentity() {
        CatalogOwnerHttpClient gateway=new CatalogOwnerHttpClient(client);
        response.set("{\"success\":true,\"data\":{\"productId\":1,\"productCode\":\"game-1\",\"ownerSubject\":\"usr_seller\"}}");
        assertThat(gateway.require("game-1").sellerSubject()).isEqualTo("usr_seller");assertUnavailable(() -> gateway.require("game-2"));
        response.set("{\"productId\":1,\"productCode\":\"game-1\",\"ownerSubject\":\"usr_seller\"}");assertUnavailable(() -> gateway.require("game-1"));
    }
    private void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }
}
