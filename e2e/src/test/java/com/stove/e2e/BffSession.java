package com.stove.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Gateway BFF 세션 하나 — 브라우저 사용자 한 명이다.
 *
 * <p>외부 경로의 OAuth 클라이언트는 기밀 {@code gateway-bff} 하나뿐이고 Gateway 는 {@code Authorization}
 * 헤더를 단 외부 요청을 거부한다(결정 219~221). 그래서 테스트도 브라우저와 같은 길로 로그인한다 —
 * {@code /oauth2/authorization/gateway-bff} → Auth 로그인 폼 → callback → {@code SESSION} 쿠키.
 *
 * <p>{@link #headers()} 는 세션 쿠키와 CSRF 헤더를 함께 담는다. GET 에 CSRF 헤더가 붙어도 무해하고,
 * 그래야 호출부가 메서드마다 헤더를 고르지 않는다. 세션 ID 는 로그인 callback 에서 한 번 바뀐 뒤
 * 유지되고(토큰 갱신은 세션 속성에 묶인 Redis 키를 바꿀 뿐이다), CSRF 토큰은 세션에 저장된 값의
 * 마스킹이라 로그인 직후 한 번 받아 둔 값을 계속 쓴다.
 */
final class BffSession {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final int MAX_REDIRECTS = 10;

    private final String gateway;
    private final CookieManager cookies;
    private final HttpClient client;
    private final String subject;
    private final long memberId;
    private final List<String> roles;
    private final Map<String, String> headers;

    private BffSession(String gateway, CookieManager cookies, HttpClient client) throws Exception {
        this.gateway = gateway;
        this.cookies = cookies;
        this.client = client;
        JsonNode session = session();
        this.subject = session.path("subject").asText();
        this.memberId = session.path("memberId").asLong();
        List<String> granted = new ArrayList<>();
        for (JsonNode role : session.path("roles")) granted.add(role.asText());
        this.roles = List.copyOf(granted);
        HttpResponse<String> csrf = get("/bff/csrf");
        if (csrf.statusCode() != 200)
            throw new IllegalStateException("BFF CSRF token unavailable: HTTP " + csrf.statusCode());
        JsonNode token = MAPPER.readTree(csrf.body());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Cookie", cookieHeader());
        values.put(token.path("headerName").asText(), token.path("token").asText());
        this.headers = Map.copyOf(values);
    }

    /** 브라우저 로그인 흐름을 HTTP 수준에서 따라가 BFF 세션을 연다. */
    static BffSession login(String email, String password) {
        String gateway = Stove.gatewayBaseUrl();
        try {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient client =
                    HttpClient.newBuilder()
                            .cookieHandler(cookies)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            HttpResponse<String> response =
                    send(client, gateway + "/oauth2/authorization/gateway-bff", null);
            boolean pkce = false;
            String loginPage = null;
            for (int redirects = 0; redirects < MAX_REDIRECTS && loginPage == null; redirects++) {
                URI location = location(response);
                if ("/oauth2/authorize".equals(location.getPath())) {
                    pkce = location.getRawQuery() != null
                            && location.getRawQuery().contains("code_challenge_method=S256");
                }
                response = send(client, onGateway(gateway, location), null);
                if ("/login".equals(location.getPath())) loginPage = response.body();
            }
            if (loginPage == null) throw new IllegalStateException("login form not reached through Gateway");
            if (!pkce) throw new IllegalStateException("BFF authorize request did not use PKCE S256");
            Matcher csrf = CSRF.matcher(loginPage);
            if (!csrf.find()) throw new IllegalStateException("login CSRF token not found");

            response = send(client, gateway + "/login",
                    "username=" + enc(email) + "&password=" + enc(password) + "&_csrf=" + enc(csrf.group(1)));
            boolean finished = false;
            for (int redirects = 0; redirects < MAX_REDIRECTS && !finished; redirects++) {
                URI location = location(response);
                if (location.getPath().startsWith("/login") && location.getRawQuery() != null
                        && location.getRawQuery().contains("error"))
                    throw new IllegalStateException("Auth rejected the login for " + email);
                if ("/".equals(location.getPath())) finished = true;
                else response = send(client, onGateway(gateway, location), null);
            }
            if (!finished) throw new IllegalStateException("BFF login did not finish");
            return new BffSession(gateway, cookies, client);
        } catch (Exception exception) {
            throw new IllegalStateException("BFF login failed", exception);
        }
    }

    /** 세션 쿠키와 CSRF 헤더. 모든 메서드에 그대로 붙인다. */
    Map<String, String> headers() {
        return headers;
    }

    /** {@code /bff/session} 의 {@code subject} — JWT {@code sub} 와 같다. */
    String subject() {
        return subject;
    }

    /** {@code /bff/session} 의 {@code memberId} — JWT {@code member_id} 클레임. */
    long memberId() {
        return memberId;
    }

    /** {@code /bff/session} 의 역할 — JWT {@code roles} 클레임. */
    List<String> roles() {
        return roles;
    }

    /** {@code GET /bff/session} 을 다시 읽는다. 토큰은 돌려주지 않는다. */
    JsonNode session() throws Exception {
        HttpResponse<String> response = get("/bff/session");
        if (response.statusCode() != 200)
            throw new IllegalStateException("BFF session unavailable: HTTP " + response.statusCode());
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(client, gateway + path, null);
    }

    private String cookieHeader() {
        List<HttpCookie> values = cookies.getCookieStore().get(URI.create(gateway));
        String header = values.stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
        if (values.stream().noneMatch(cookie -> "SESSION".equals(cookie.getName())))
            throw new IllegalStateException("Gateway did not issue a SESSION cookie");
        return header;
    }

    private static HttpResponse<String> send(HttpClient client, String uri, String form) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri));
        if (form == null) request.GET();
        else request.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI location(HttpResponse<String> response) {
        String location = response.headers().firstValue("location").orElseThrow(
                () -> new IllegalStateException("redirect missing: HTTP " + response.statusCode()
                        + " " + response.uri().getPath()));
        return response.uri().resolve(location);
    }

    /**
     * 공개 주소({@code BFF_PUBLIC_AUTH_BASE})가 E2E 의 Gateway 주소와 호스트 표기만 다를 수 있다
     * ({@code localhost} 와 {@code 127.0.0.1}). 쿠키 저장소가 호스트별이라 경로와 질의만 살려 Gateway 로 보낸다.
     */
    private static String onGateway(String gateway, URI location) {
        String query = location.getRawQuery() == null ? "" : "?" + location.getRawQuery();
        return gateway + location.getRawPath() + query;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
