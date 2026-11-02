package com.stove.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogSalesGatewayTest {
    private static final String SELLER="/api/v1/products/by-code/game/sales";
    private static final String ADMIN="/api/v1/admin/products/game/sales/restriction";
    private static final AtomicInteger CALLS=new AtomicInteger();
    private static final AtomicReference<String> BODY=new AtomicReference<>();
    private static final AtomicReference<String> AUTH=new AtomicReference<>();
    private static final AtomicReference<String> SPOOFED=new AtomicReference<>();
    private static final HttpServer CATALOG;
    static {
        try {
            CATALOG=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            CATALOG.createContext("/",exchange -> {
                CALLS.incrementAndGet(); BODY.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                AUTH.set(exchange.getRequestHeaders().getFirst("Authorization")); SPOOFED.set(exchange.getRequestHeaders().getFirst("X-Member-Id"));
                byte[] body="{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            CATALOG.start();Runtime.getRuntime().addShutdownHook(new Thread(() -> CATALOG.stop(0)));
        } catch(Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @Autowired WebTestClient http;
    @MockitoBean ReactiveJwtDecoder decoder;
    @DynamicPropertySource static void catalog(DynamicPropertyRegistry p) { p.add("CATALOG_URI",() -> "http://127.0.0.1:"+CATALOG.getAddress().getPort()); }
    @BeforeEach void tokens() {
        CALLS.set(0);BODY.set(null);AUTH.set(null);SPOOFED.set(null);
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role=call.getArgument(0,String.class);
            return Mono.just(Jwt.withTokenValue(role).header("alg","RS256").subject("subject").claim("roles",List.of(role)).build());
        });
    }
    @Test void sellerRouteRelaysOnlyCreatorRequestsAndPreservesTheBearerAndBody() {
        http.put().uri(SELLER).header("Authorization","Bearer CREATOR").header("X-Member-Id","spoof")
                .header("Content-Type","application/json").bodyValue("{\"saleStatus\":\"STOPPED\",\"expectedCatalogVersion\":1}")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store");
        assertThat(BODY.get()).contains("STOPPED","expectedCatalogVersion"); assertThat(AUTH.get()).isEqualTo("Bearer CREATOR"); assertThat(SPOOFED.get()).isNull();
        int before=CALLS.get();
        http.put().uri(SELLER).header("Authorization","Bearer ADMIN").exchange().expectStatus().isForbidden();
        http.get().uri(SELLER).header("Authorization","Bearer MEMBER").exchange().expectStatus().isForbidden();
        http.get().uri(SELLER).exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isEqualTo(before);
    }
    @Test void downloadRestrictionRoutePreservesBodyAndRejectsEveryNonAdministrator() {
        String path = "/api/v1/admin/products/game/downloads/restriction";
        http.put().uri(path).header("Authorization", "Bearer ADMIN").header("Content-Type", "application/json")
                .bodyValue("{\"restricted\":true,\"expectedRestrictionVersion\":1,\"reason\":\"unsafe file\"}")
                .exchange().expectStatus().isOk();
        assertThat(BODY.get()).contains("unsafe file", "expectedRestrictionVersion");
        assertThat(AUTH.get()).isEqualTo("Bearer ADMIN");
        http.get().uri(path).header("Authorization", "Bearer ADMIN").exchange().expectStatus().isOk();
        int before = CALLS.get();
        for (String role : List.of("CREATOR", "MEMBER")) {
            http.get().uri(path).header("Authorization", "Bearer " + role).exchange().expectStatus().isForbidden();
            http.put().uri(path).header("Authorization", "Bearer " + role).exchange().expectStatus().isForbidden();
        }
        http.get().uri(path).exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void sellerAndAdministratorListingRoutesHaveIndependentRoles() {
        String own = "/api/v1/products/by-code/game/listing";
        String admin = "/api/v1/admin/products/game/listing/restriction";
        http.put().uri(own).header("Authorization", "Bearer CREATOR").header("Content-Type", "application/json")
                .header("X-Member-Id", "spoof").bodyValue("{\"preference\":\"AUTO\",\"expectedCatalogVersion\":1}")
                .exchange().expectStatus().isOk();
        assertThat(AUTH.get()).isEqualTo("Bearer CREATOR"); assertThat(SPOOFED.get()).isNull();
        assertThat(BODY.get()).contains("AUTO");
        http.get().uri(own).header("Authorization", "Bearer CREATOR").exchange().expectStatus().isOk();
        http.put().uri(admin).header("Authorization", "Bearer ADMIN").header("Content-Type", "application/json")
                .bodyValue("{\"hidden\":true,\"expectedCatalogVersion\":1}").exchange().expectStatus().isOk();
        http.get().uri(admin).header("Authorization", "Bearer ADMIN").exchange().expectStatus().isOk();
        int before = CALLS.get();
        http.put().uri(own).header("Authorization", "Bearer ADMIN").exchange().expectStatus().isForbidden();
        http.get().uri(own).header("Authorization", "Bearer MEMBER").exchange().expectStatus().isForbidden();
        http.put().uri(admin).header("Authorization", "Bearer CREATOR").exchange().expectStatus().isForbidden();
        http.get().uri(admin).exchange().expectStatus().isUnauthorized();
        http.get().uri("/internal/v1/store-products").header("X-Store-Service-Key", "spoof").exchange().expectStatus().isUnauthorized();
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void platformRestrictionRouteAcceptsOnlyAdministrators() {
        http.put().uri(ADMIN).header("Authorization","Bearer ADMIN").header("Content-Type","application/json")
                .bodyValue("{\"restricted\":true,\"expectedCatalogVersion\":1}").exchange().expectStatus().isOk();
        int before=CALLS.get();
        http.put().uri(ADMIN).header("Authorization","Bearer CREATOR").exchange().expectStatus().isForbidden();
        assertThat(CALLS.get()).isEqualTo(before);
    }
}
