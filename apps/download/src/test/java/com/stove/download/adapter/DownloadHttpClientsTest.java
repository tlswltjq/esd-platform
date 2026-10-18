package com.stove.download.adapter;

import static org.assertj.core.api.Assertions.*;
import com.stove.common.web.error.*;
import com.stove.download.config.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class DownloadHttpClientsTest {
    HttpServer server;
    String base;
    int status;
    String body;
    String authorization;
    String key;
    String path;
    String location;
    AtomicInteger calls = new AtomicInteger();
    long delay;
    LicenseHttpClient license;
    CatalogBuildHttpClient catalog;
    static final String OWNED = "{\"success\":true,\"data\":{\"productId\":1,\"productCode\":\"game\"}}";
    static final String BUILD = "{\"success\":true,\"data\":{\"productId\":1,\"productCode\":\"game\",\"releaseRequestId\":2,\"contentVersion\":3,\"buildId\":4,\"version\":\"v1\",\"fileSize\":10,\"checksum\":\"" + "a".repeat(64) + "\",\"storagePath\":\"s3://stove-builds/game.zip\"}}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path = exchange.getRequestURI().getPath();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            key = exchange.getRequestHeaders().getFirst("X-Download-Service-Key");
            try {
                if (delay > 0) Thread.sleep(delay);
                if (location != null) exchange.getResponseHeaders().set("Location", location);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort(); status = 200; body = OWNED;
        DownloadClientProperties props = new DownloadClientProperties(base, base, "service-key", Duration.ofSeconds(1), Duration.ofSeconds(3));
        DownloadClientConfiguration config = new DownloadClientConfiguration();
        license = config.ownedProducts(props); catalog = config.publishedBuilds(props);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void forwardsUserTokenOnlyToLibraryAndServiceKeyOnlyToCatalog() {
        assertThat(license.require("game", "user-token").productId()).isEqualTo(1);
        assertThat(path).isEqualTo("/api/v1/library/game"); assertThat(authorization).isEqualTo("Bearer user-token"); assertThat(key).isNull();
        body = BUILD;
        assertThat(catalog.require("game").buildId()).isEqualTo(4);
        assertThat(path).isEqualTo("/internal/v1/products/game/build"); assertThat(key).isEqualTo("service-key"); assertThat(authorization).isNull();
    }
    @ParameterizedTest @CsvSource({"401,UNAUTHORIZED", "403,FORBIDDEN", "404,FORBIDDEN", "500,UPSTREAM_UNAVAILABLE", "302,UPSTREAM_UNAVAILABLE"})
    void distinguishesLibraryDenialsAndAvailability(int response, ErrorCode expected) {
        status = response;
        assertThatThrownBy(() -> license.require("game", "token")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    @ParameterizedTest @CsvSource({"401,UPSTREAM_UNAVAILABLE", "403,UPSTREAM_UNAVAILABLE", "404,NOT_FOUND", "500,UPSTREAM_UNAVAILABLE", "302,UPSTREAM_UNAVAILABLE"})
    void mapsCatalogStatuses(int response, ErrorCode expected) {
        status = response;
        assertThatThrownBy(() -> catalog.require("game")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    @ParameterizedTest @ValueSource(strings={"null", "{}", "[]", "broken", "{\"success\":true,\"data\":null}", "{\"success\":false,\"data\":{}}"})
    void rejectsMalformedSuccessResponses(String response) {
        body = response;
        assertUnavailable(() -> license.require("game", "token")); assertUnavailable(() -> catalog.require("game"));
    }
    @ParameterizedTest @ValueSource(strings={"0", "-1", "1.5", "9223372036854775808", "\"1\"", "null"})
    void rejectsInvalidAndCoercedIds(String id) {
        body = OWNED.replace("\"productId\":1", "\"productId\":" + id);
        assertUnavailable(() -> license.require("game", "token"));
        body = BUILD.replace("\"productId\":1", "\"productId\":" + id);
        assertUnavailable(() -> catalog.require("game"));
    }
    @Test void rejectsMismatchedCodesAndBuildFields() {
        body = OWNED.replace("\"game\"", "\"other\""); assertUnavailable(() -> license.require("game", "token"));
        for (String invalid : new String[]{BUILD.replace("\"game\"", "\"other\""), BUILD.replace("\"fileSize\":10", "\"fileSize\":0"), BUILD.replace("a".repeat(64), "short")}) {
            body = invalid; assertUnavailable(() -> catalog.require("game"));
        }
    }
    @Test void redirectsNeverForwardCredentialsToAnotherRequest() {
        status = 302; location = base + "/redirect-target";
        assertUnavailable(() -> license.require("game", "token")); assertThat(calls.get()).isEqualTo(1);
        assertUnavailable(() -> catalog.require("game")); assertThat(calls.get()).isEqualTo(2);
    }
    @Test void finiteReadTimeoutIsUnavailable() {
        delay = 400;
        LicenseHttpClient bounded = new DownloadClientConfiguration().ownedProducts(new DownloadClientProperties(
                base, base, "service-key", Duration.ofSeconds(1), Duration.ofMillis(100)));
        assertUnavailable(() -> bounded.require("game", "token"));
    }
    @Test void refusedConnectionIsUnavailable() { server.stop(0); assertUnavailable(() -> catalog.require("game")); }
    static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }
}
