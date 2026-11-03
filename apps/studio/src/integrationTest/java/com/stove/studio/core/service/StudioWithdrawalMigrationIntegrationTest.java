package com.stove.studio.core.service;

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

class StudioWithdrawalMigrationIntegrationTest {
    @Test
    void preservesRequestHistoryAndEnforcesOneActiveReviewAcrossVersions() {
        withOldSchema((jdbc, migration) -> {
            project(jdbc);
            insert(jdbc, 11, 1, "APPROVED");
            insert(jdbc, 12, 2, "REQUESTED");
            jdbc.update("INSERT INTO studio_release_request(id,project_id,review_request_id,content_version,status,created_at,updated_at) VALUES(21,1,11,1,'COMPLETED',NOW(),NOW())");
            Map<String, Object> review = jdbc.queryForMap("SELECT * FROM studio_review_request WHERE id=11");
            Map<String, Object> release = jdbc.queryForMap("SELECT * FROM studio_release_request WHERE id=21");
            migration.migrate();
            assertThat(jdbc.queryForMap("SELECT * FROM studio_review_request WHERE id=11")).containsAllEntriesOf(review);
            assertThat(jdbc.queryForMap("SELECT * FROM studio_release_request WHERE id=21")).containsAllEntriesOf(release);
            assertThatThrownBy(() -> insert(jdbc, 13, 3, "REQUESTED")).isInstanceOf(DuplicateKeyException.class);
            jdbc.update("UPDATE studio_review_request SET status='WITHDRAWN' WHERE id=12");
            insert(jdbc, 13, 3, "REQUESTED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM studio_request_command", Integer.class)).isZero();
            insertRelease(jdbc, 22, 2, "REQUESTED");
            assertThatThrownBy(() -> insertRelease(jdbc, 23, 3, "REQUESTED")).isInstanceOf(DuplicateKeyException.class);
            jdbc.update("UPDATE studio_release_request SET status='WITHDRAWN' WHERE id=22");
            insertRelease(jdbc, 23, 3, "REQUESTED");
        });
    }

    @Test
    void refusesConflictingLegacyActiveReviewsWithoutChoosingOneAutomatically() {
        withOldSchema((jdbc, migration) -> {
            project(jdbc);
            insert(jdbc, 11, 1, "REQUESTED");
            insert(jdbc, 12, 2, "REQUESTED");
            assertThatThrownBy(migration::migrate).isInstanceOf(FlywayException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM studio_review_request WHERE status='REQUESTED'", Integer.class)).isEqualTo(2);
        });
    }

    private void project(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO studio_project(id,product_code,owner_subject,title,content,content_version,created_at,updated_at) VALUES(1,'MIGRATION','owner','Game','Description',3,NOW(),NOW())");
    }

    @Test
    void refusesConflictingLegacyActiveReleasesWithoutDeletingHistory() {
        withOldSchema((jdbc, migration) -> {
            project(jdbc);
            insert(jdbc, 11, 1, "APPROVED");
            insertRelease(jdbc, 22, 1, "REQUESTED");
            insertRelease(jdbc, 23, 2, "REQUESTED");
            assertThatThrownBy(migration::migrate).isInstanceOf(FlywayException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM studio_release_request WHERE status='REQUESTED'", Integer.class)).isEqualTo(2);
        });
    }

    private void insertRelease(JdbcTemplate jdbc, long id, long version, String status) {
        jdbc.update("INSERT INTO studio_release_request(id,project_id,review_request_id,content_version,status,created_at,updated_at) VALUES(?,1,11,?,?,NOW(),NOW())",
                id, version, status);
    }

    private void insert(JdbcTemplate jdbc, long id, long version, String status) {
        jdbc.update("INSERT INTO studio_review_request(id,project_id,content_version,build_id,snapshot,status,created_at,updated_at) VALUES(?,1,?,1,'{}',?,NOW(),NOW())",
                id, version, status);
    }

    private void withOldSchema(BiConsumer<JdbcTemplate, Flyway> check) {
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        mysql.start();
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(
                server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        String schema = "studio_withdrawal_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("25").load().migrate();
            check.accept(new JdbcTemplate(source), Flyway.configure().dataSource(source).locations(location).load());
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
