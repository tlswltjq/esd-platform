package com.stove.review.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class ReviewWithdrawalMigrationIntegrationTest {
    @Test
    void preservesHistoryAndOnlyOneActiveReviewCanExistAcrossVersions() {
        withOldSchema((jdbc, migration) -> {
            insert(jdbc, 11, 1, "APPROVED");
            insert(jdbc, 12, 2, "REQUESTED");
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM project_review WHERE review_request_id=11");
            migration.migrate();
            assertThat(jdbc.queryForMap("SELECT * FROM project_review WHERE review_request_id=11")).containsAllEntriesOf(before);
            assertThatThrownBy(() -> insert(jdbc, 13, 3, "REQUESTED")).isInstanceOf(DuplicateKeyException.class);
            jdbc.update("UPDATE project_review SET status='WITHDRAWN' WHERE review_request_id=12");
            insert(jdbc, 13, 3, "REQUESTED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_review", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_withdrawal", Integer.class)).isZero();
        });
    }

    @Test
    void refusesConflictingLegacyActiveReviewsWithoutDeletingEitherRequest() {
        withOldSchema((jdbc, migration) -> {
            insert(jdbc, 11, 1, "REQUESTED");
            insert(jdbc, 12, 2, "REQUESTED");
            assertThatThrownBy(migration::migrate).isInstanceOf(FlywayException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_review WHERE status='REQUESTED'", Integer.class)).isEqualTo(2);
        });
    }

    private void insert(JdbcTemplate jdbc, long id, long version, String status) {
        jdbc.update("INSERT INTO project_review(review_request_id,request_event_id,project_id,product_code,owner_subject,content_version,snapshot,status,requested_at,created_at,updated_at) VALUES(?, ?, 1, 'MIGRATION', 'owner', ?, '{}', ?, NOW(), NOW(), NOW())",
                id, "event-" + id, version, status);
    }

    private void withOldSchema(BiConsumer<JdbcTemplate, Flyway> check) {
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        mysql.start();
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(
                server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        String schema = "review_withdrawal_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("10").load().migrate();
            check.accept(new JdbcTemplate(source), Flyway.configure().dataSource(source).locations(location).load());
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
