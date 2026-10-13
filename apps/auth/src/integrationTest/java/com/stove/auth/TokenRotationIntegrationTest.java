package com.stove.auth;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.testcontainers.InfraContainers;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.outbox.relay-enabled=false", "stove.auth.bff-client-secret=integration-secret",
        "stove.auth.bff-redirect-uri=http://localhost:3000/login/oauth2/code/gateway-bff"})
@Import(InfraContainers.MySql.class)
class TokenRotationIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String CALLBACK = "http://localhost:3000/login/oauth2/code/gateway-bff";
    private String base() { return "http://localhost:" + port; }
    private String enc(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, String body, boolean basic) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (basic) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("gateway-bff:integration-secret".getBytes(StandardCharsets.UTF_8)));
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode login() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        String email = "integration-" + UUID.randomUUID() + "@example.com";
        String password = "integration-password-123";
        var signup = client.send(HttpRequest.newBuilder(URI.create(base()+"/api/v1/auth/signup/member"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("email",email,"password",password)))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(signup.statusCode()).isEqualTo(200);
        String verifier = UUID.randomUUID().toString() + UUID.randomUUID();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var first = get(client, base()+"/oauth2/authorize?response_type=code&client_id=gateway-bff&scope="+enc("openid profile")
                +"&redirect_uri="+enc(CALLBACK)+"&code_challenge="+challenge+"&code_challenge_method=S256");
        assertThat(first.statusCode()).isEqualTo(302);
        var page = get(client, base()+"/login");
        var csrf = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
        assertThat(csrf.find()).isTrue();
        var response = post(client,"/login","username="+enc(email)+"&password="+enc(password)+"&_csrf="+enc(csrf.group(1)),false);
        String location = response.headers().firstValue("Location").orElseThrow();
        for(int i=0; i<5 && !location.startsWith(CALLBACK); i++) {
            URI uri = URI.create(location);
            response = get(client,base()+uri.getRawPath()+"?"+uri.getRawQuery());
            location = response.headers().firstValue("Location").orElseThrow();
        }
        assertThat(location).startsWith(CALLBACK);
        String code = Arrays.stream(URI.create(location).getRawQuery().split("&")).filter(pair -> pair.startsWith("code="))
                .map(pair -> URLDecoder.decode(pair.substring(5),StandardCharsets.UTF_8)).findFirst().orElseThrow();
        var token = post(client,"/oauth2/token","grant_type=authorization_code&code="+enc(code)+"&redirect_uri="+enc(CALLBACK)+"&code_verifier="+enc(verifier),true);
        assertThat(token.statusCode()).isEqualTo(200);
        JsonNode tokens = mapper.readTree(token.body());
        assertThat(tokens.hasNonNull("refresh_token")).isTrue();
        var userinfo = client.send(HttpRequest.newBuilder(URI.create(base()+"/userinfo"))
                .header("Authorization","Bearer "+tokens.path("access_token").asText()).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(userinfo.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(userinfo.body()).hasNonNull("sub")).isTrue();
        assertThat(get(client, base()+"/favicon.ico").statusCode()).isEqualTo(404);
        return tokens;
    }
    private HttpResponse<String> refresh(String token) throws Exception {
        return post(HttpClient.newHttpClient(),"/oauth2/token","grant_type=refresh_token&refresh_token="+enc(token),true);
    }
    @Test void rotationReplayRevokesLatestAndLifetimeDoesNotExtend() throws Exception {
        JsonNode initial = login();
        String old = initial.path("refresh_token").asText();
        var nextResponse = refresh(old); assertThat(nextResponse.statusCode()).isEqualTo(200);
        String next = mapper.readTree(nextResponse.body()).path("refresh_token").asText();
        assertThat(next).isNotEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token_history WHERE consumed = TRUE AND token_hash = ?",Integer.class,
                com.stove.auth.infrastructure.oauth.RotatingAuthorizationService.hash(old))).isEqualTo(1);
        var expires = jdbc.queryForMap("SELECT f.expires_at AS absolute_expiry, a.refresh_token_expires_at AS current_expiry FROM refresh_token_family f JOIN oauth2_authorization a ON a.id=f.authorization_id JOIN refresh_token_history h ON h.authorization_id=f.authorization_id WHERE h.token_hash=?",
                com.stove.auth.infrastructure.oauth.RotatingAuthorizationService.hash(next));
        assertThat(expires.get("absolute_expiry")).isEqualTo(expires.get("current_expiry"));
        assertThat(refresh(old).statusCode()).isEqualTo(400);
        assertThat(refresh(next).statusCode()).isEqualTo(400);
    }
    @Test void simultaneousRefreshConsumesOnlyOnce() throws Exception {
        String token = login().path("refresh_token").asText();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<HttpResponse<String>> task = () -> { start.await(); return refresh(token); };
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS).statusCode(),second.get(15,TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200,400);
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredFamilyCannotRefresh() throws Exception {
        String token = login().path("refresh_token").asText();
        jdbc.update("UPDATE refresh_token_family SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE authorization_id=(SELECT authorization_id FROM refresh_token_history WHERE token_hash=?)",
                com.stove.auth.infrastructure.oauth.RotatingAuthorizationService.hash(token));
        assertThat(refresh(token).statusCode()).isEqualTo(400);
    }
    @Test void disabledAccountCannotRefresh() throws Exception {
        JsonNode token = login();
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(token.path("access_token").asText().split("\\.")[1]));
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE subject=?",claims.path("sub").asText());
        assertThat(refresh(token.path("refresh_token").asText()).statusCode()).isEqualTo(400);
    }
    @Test void revocationPreventsRefresh() throws Exception {
        String token = login().path("refresh_token").asText();
        var response = post(HttpClient.newHttpClient(),"/oauth2/revoke","token="+enc(token)+"&token_type_hint=refresh_token",true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(refresh(token).statusCode()).isEqualTo(400);
    }

    @Test void passwordChangeRevokesExistingRefreshTokens() throws Exception {
        JsonNode tokens = login();
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base()+"/api/v1/auth/password"))
                .header("Authorization", "Bearer " + tokens.path("access_token").asText())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "currentPassword", "integration-password-123", "newPassword", "updated-password-123")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(refresh(tokens.path("refresh_token").asText()).statusCode()).isEqualTo(400);
    }

    @Test void refreshedAccessTokenUsesCurrentRoles() throws Exception {
        JsonNode initial = login();
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(initial.path("access_token").asText().split("\\.")[1]));
        jdbc.update("INSERT INTO user_account_role (user_account_id,role) VALUES (?, 'CREATOR')", claims.path("member_id").asLong());
        var response = refresh(initial.path("refresh_token").asText());
        assertThat(response.statusCode()).isEqualTo(200);
        String access = mapper.readTree(response.body()).path("access_token").asText();
        JsonNode updated = mapper.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        assertThat(updated.path("roles").toString()).contains("CREATOR");
    }

    @Test void idTokenCannotAuthenticateAnApiRequest() throws Exception {
        JsonNode tokens = login();
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base()+"/api/v1/auth/session"))
                .header("Authorization", "Bearer " + tokens.path("id_token").asText()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
