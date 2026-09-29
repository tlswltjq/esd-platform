package com.stove.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** 실제 Authorization Code + PKCE 브라우저 흐름을 HTTP 수준에서 수행한다. */
final class OidcLogin {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final String REDIRECT_URI = "http://localhost:3000/callback";
    private static final String AUTH = "http://127.0.0.1:18091";

    private OidcLogin() {
    }

    static String token(String email, String password) {
        try {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            String verifier = verifier();
            String authorize = AUTH + "/oauth2/authorize?response_type=code&client_id=studio-web"
                    + "&scope=" + enc("openid profile studio")
                    + "&redirect_uri=" + enc(REDIRECT_URI)
                    + "&code_challenge=" + enc(challenge(verifier))
                    + "&code_challenge_method=S256";

            HttpResponse<String> first = get(client, authorize);
            HttpResponse<String> loginPage = get(client, resolve(first.headers().firstValue("location")
                    .orElse("/login")));
            var csrf = CSRF.matcher(loginPage.body());
            if (!csrf.find()) throw new IllegalStateException("login CSRF token not found");

            HttpRequest login = HttpRequest.newBuilder(URI.create(AUTH + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + enc(email)
                            + "&password=" + enc(password) + "&_csrf=" + enc(csrf.group(1))))
                    .build();
            HttpResponse<String> response = client.send(login, HttpResponse.BodyHandlers.ofString());
            String location = response.headers().firstValue("location").orElseThrow();
            for (int redirects = 0; redirects < 5 && !location.startsWith(REDIRECT_URI); redirects++) {
                response = get(client, resolve(location));
                location = response.headers().firstValue("location").orElseThrow();
            }
            String code = queryParam(URI.create(location).getRawQuery(), "code");

            HttpRequest token = HttpRequest.newBuilder(URI.create(AUTH + "/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code"
                            + "&client_id=studio-web&code=" + enc(code)
                            + "&redirect_uri=" + enc(REDIRECT_URI)
                            + "&code_verifier=" + enc(verifier)))
                    .build();
            HttpResponse<String> tokenResponse = client.send(token, HttpResponse.BodyHandlers.ofString());
            if (tokenResponse.statusCode() != 200) {
                throw new IllegalStateException("token exchange failed: " + tokenResponse.body());
            }
            JsonNode json = MAPPER.readTree(tokenResponse.body());
            return json.path("access_token").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("OIDC login failed", exception);
        }
    }

    private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String resolve(String location) {
        if (location.startsWith("http://localhost:8091")) {
            return AUTH + location.substring("http://localhost:8091".length());
        }
        return location.startsWith("http") ? location : AUTH + location;
    }

    private static String verifier() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("missing query parameter " + name);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
