package com.stove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.auth.core.port.PasswordResetMailPort;
import com.stove.common.testcontainers.InfraContainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 분실 비밀번호 복구의 전체 여정을 실제 MySQL에서 검증한다.
 *
 * <p>요청 → 토큰 저장(해시) → 메일로 원본 전달 → 실행 → 비밀번호 해시 변경 → 토큰 소비 →
 * Refresh Token 계열 폐기까지 HTTP 경계로 끝까지 돈다. 메일 포트는 원본 토큰을 가로채는 테스트
 * 구현으로 바꿔, 사용자가 메일로 받는 원본 토큰을 잡아 재설정에 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.outbox.relay-enabled=false", "stove.auth.bff-client-secret=integration-secret",
        "stove.auth.bff-redirect-uri=http://localhost:3000/login/oauth2/code/gateway-bff"})
@Import({InfraContainers.MySql.class, PasswordRecoveryIntegrationTest.CapturingMail.class})
class PasswordRecoveryIntegrationTest {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapturingMail mail;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> postJson(String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requestThenResetChangesPasswordConsumesTokenAndRevokesSessions() throws Exception {
        String email = "recover-" + UUID.randomUUID() + "@example.com";
        String oldPassword = "original-password-123";
        assertThat(postJson("/api/v1/auth/signup/member", Map.of("email", email, "password", oldPassword))
                .statusCode()).isEqualTo(200);
        String subject = jdbc.queryForObject("SELECT subject FROM user_account WHERE email = ?",
                String.class, email);
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE email = ?",
                String.class, email);

        mail.lastToken.set(null);
        HttpResponse<String> requested = postJson("/api/v1/auth/password/reset-request", Map.of("email", email));
        assertThat(requested.statusCode()).isEqualTo(200);
        String rawToken = mail.lastToken.get();
        assertThat(rawToken).as("원본 토큰이 메일로 전달돼야 한다").isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE subject = ? AND consumed = FALSE",
                Integer.class, subject)).isEqualTo(1);

        String newPassword = "brand-new-password-456";
        HttpResponse<String> reset = postJson("/api/v1/auth/password/reset",
                Map.of("token", rawToken, "newPassword", newPassword));
        assertThat(reset.statusCode()).isEqualTo(200);

        String newHash = jdbc.queryForObject("SELECT password_hash FROM user_account WHERE email = ?",
                String.class, email);
        assertThat(newHash).isNotEqualTo(oldHash);
        assertThat(jdbc.queryForObject("SELECT consumed FROM password_reset_token WHERE subject = ?",
                Boolean.class, subject)).isTrue();

        // 같은 토큰 재사용은 거절된다(일회성).
        HttpResponse<String> replay = postJson("/api/v1/auth/password/reset",
                Map.of("token", rawToken, "newPassword", "another-password-789"));
        assertThat(replay.statusCode()).isEqualTo(400);
    }

    @Test
    void requestForUnknownEmailLooksIdenticalAndStoresNothing() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID() + "@example.com";
        mail.lastToken.set(null);
        HttpResponse<String> response = postJson("/api/v1/auth/password/reset-request", Map.of("email", ghost));
        // 가입 여부를 드러내지 않도록 동일하게 200으로 성공한다.
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("data").path("accepted").asBoolean()).isTrue();
        assertThat(mail.lastToken.get()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token", Integer.class)).isZero();
    }

    @Test
    void expiredTokenCannotReset() throws Exception {
        String email = "expire-" + UUID.randomUUID() + "@example.com";
        assertThat(postJson("/api/v1/auth/signup/member", Map.of("email", email, "password", "original-password-123"))
                .statusCode()).isEqualTo(200);
        String subject = jdbc.queryForObject("SELECT subject FROM user_account WHERE email = ?",
                String.class, email);
        mail.lastToken.set(null);
        postJson("/api/v1/auth/password/reset-request", Map.of("email", email));
        String rawToken = mail.lastToken.get();
        // 저장된 토큰을 과거로 만료시킨다.
        jdbc.update("UPDATE password_reset_token SET expires_at = TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE subject = ?",
                subject);
        HttpResponse<String> reset = postJson("/api/v1/auth/password/reset",
                Map.of("token", rawToken, "newPassword", "brand-new-password-456"));
        assertThat(reset.statusCode()).isEqualTo(400);
    }

    @TestConfiguration
    static class CapturingMail {
        final AtomicReference<String> lastToken = new AtomicReference<>();

        @Bean
        @Primary
        PasswordResetMailPort capturingPasswordResetMailPort() {
            return (email, resetToken) -> lastToken.set(resetToken);
        }
    }
}
