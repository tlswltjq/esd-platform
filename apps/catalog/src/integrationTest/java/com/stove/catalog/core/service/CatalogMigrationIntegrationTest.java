package com.stove.catalog.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.catalog.CatalogIntegrationTestBase;
import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

class CatalogMigrationIntegrationTest extends CatalogIntegrationTestBase {
    @Test
    void v11PreservesLegacyProductsWithoutPublishingThem() throws Exception {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "catalog_migration_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                server
                                        + mysql.getDatabaseName()
                                        + "?useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source =
                    new DriverManagerDataSource(
                            server + schema + "?useSSL=false&allowPublicKeyRetrieval=true",
                            "root",
                            mysql.getPassword());
            String location =
                    "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("10").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            int legacy = jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class);
            assertThat(legacy).isPositive();
            jdbc.update(
                    "INSERT INTO"
                        + " outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at)"
                        + " VALUES('legacy','Product','OLD','ProductChanged','stove.catalog.v1','OLD','{}','PENDING',NOW())");
            try (Connection connection = source.getConnection()) {
                FileSystemResource script =
                        new FileSystemResource(
                                Path.of("../../tools/migrations/catalog/catalog-transition.sql"));
                ScriptUtils.executeSqlScript(
                        connection, script);
                ScriptUtils.executeSqlScript(
                        connection, script);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM outbox_event WHERE event_id='legacy'",
                                    String.class))
                    .isEqualTo("DEAD");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM catalog_legacy_outbox_archive WHERE"
                                        + " event_id='legacy'",
                                    String.class))
                    .isEqualTo("PENDING");
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class))
                    .isEqualTo(legacy);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_release", Integer.class))
                    .isZero();
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
