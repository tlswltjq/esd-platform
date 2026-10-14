package com.stove.review.core.service;

import static org.assertj.core.api.Assertions.*;

import com.stove.common.testcontainers.SharedContainers;
import com.stove.review.ReviewIntegrationTestBase;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.UUID;

class ReviewMigrationIntegrationTest extends ReviewIntegrationTestBase {
    @Test
    void v10KeepsHistoricalApprovalsSeparateFromNewStudioRequestIds() {
        var mysql = SharedContainers.MYSQL;
        String schema = "review_migration_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        var root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                server
                                        + mysql.getDatabaseName()
                                        + "?useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            var source =
                    new DriverManagerDataSource(
                            server + schema + "?useSSL=false&allowPublicKeyRetrieval=true",
                            "root",
                            mysql.getPassword());
            String location =
                    "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("9").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update(
                    "INSERT INTO"
                        + " review_request(id,game_id,product_code,title,seller_id,price,self_rated,status,created_at,updated_at)"
                        + " VALUES(7,1,'LEGACY','Legacy',1,1000,0,'APPROVED',NOW(),NOW())");
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM review_request WHERE id=7", String.class))
                    .isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_review", Integer.class))
                    .isZero();
            jdbc.update(
                    "INSERT INTO"
                        + " project_review(review_request_id,request_event_id,project_id,product_code,owner_subject,content_version,snapshot,status,requested_at,created_at,updated_at)"
                        + " VALUES(7,'new-event',1,'LEGACY','creator',1,'{}','REQUESTED',NOW(),NOW(),NOW())");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM project_review WHERE review_request_id=7",
                                    String.class))
                    .isEqualTo("REQUESTED");
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
