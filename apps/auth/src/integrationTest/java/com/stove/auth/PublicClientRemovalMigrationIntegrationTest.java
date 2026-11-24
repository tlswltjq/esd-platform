package com.stove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 결정 220: V11은 예전 시작이 저장한 공개 클라이언트 studio-web·swagger-ui와 그 인가·동의·Refresh 계열을
 * 외래 키 순서대로 지우고, gateway-bff와 그 기록은 그대로 둔다.
 */
class PublicClientRemovalMigrationIntegrationTest {
    private static final String LOCATION = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();

    @Test
    void v11RemovesPublicClientsWithTheirRecordsAndKeepsTheBffClient() {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        mysql.start(); // 다른 통합 테스트가 먼저 띄웠으면 그대로 쓴다.
        String schema = "auth_v11_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema
                    + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            Flyway.configure().dataSource(source).locations(LOCATION).target("10").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            for (String client : List.of("studio-web", "swagger-ui", "gateway-bff")) {
                seed(jdbc, client);
            }

            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).target("11").load();
            flyway.migrate();
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT client_id FROM oauth2_registered_client", String.class))
                    .containsExactly("gateway-bff");
            assertThat(jdbc.queryForList("SELECT registered_client_id FROM oauth2_authorization", String.class))
                    .containsExactly("gateway-bff");
            assertThat(jdbc.queryForList("SELECT registered_client_id FROM oauth2_authorization_consent", String.class))
                    .containsExactly("gateway-bff");
            assertThat(jdbc.queryForList("SELECT client_id FROM refresh_token_family", String.class))
                    .containsExactly("gateway-bff");
            assertThat(jdbc.queryForList("SELECT authorization_id FROM refresh_token_history", String.class))
                    .containsExactly("authorization-gateway-bff");
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }

    private static void seed(JdbcTemplate jdbc, String client) {
        jdbc.update("""
                INSERT INTO oauth2_registered_client (id, client_id, client_name, client_authentication_methods,
                    authorization_grant_types, scopes, client_settings, token_settings)
                VALUES (?, ?, ?, 'none', 'authorization_code', 'openid', '{}', '{}')
                """, client, client, client);
        String authorization = "authorization-" + client;
        jdbc.update("INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type)"
                + " VALUES (?, ?, 'member', 'authorization_code')", authorization, client);
        jdbc.update("INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)"
                + " VALUES (?, 'member', 'SCOPE_openid')", client);
        jdbc.update("INSERT INTO refresh_token_family (authorization_id, client_id, subject, expires_at)"
                + " VALUES (?, ?, 'member', CURRENT_TIMESTAMP(6))", authorization, client);
        jdbc.update("INSERT INTO refresh_token_history (token_hash, authorization_id) VALUES (?, ?)",
                String.format("%064d", Math.abs(client.hashCode())), authorization);
    }
}
